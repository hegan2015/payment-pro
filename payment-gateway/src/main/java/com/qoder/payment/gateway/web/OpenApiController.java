package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.ResourceTypeConstants;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.qoder.payment.common.sentinel.SentinelGuard;
import com.qoder.payment.gateway.config.SentinelConfig;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.payment.FundPayRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** OpenAPI：商户 appId + HMAC-SHA256 签名接入，对标支付宝开放平台网关。 */
@Slf4j
@RestController
@RequestMapping("/openapi")
public class OpenApiController {

    private static final long TIMESTAMP_TOLERANCE_MS = 300_000L;

    /** I-7 消费端熔断资源（与 sentinel-rules.json degradeRules 资源名一致，接口级）。 */
    private static final String RES_MEMBER = "com.qoder.payment.facade.member.MemberFacade";
    private static final String RES_TRADE = "com.qoder.payment.facade.trade.TradeFacade";
    private static final String RES_PAYMENT = "com.qoder.payment.facade.payment.PaymentFacade";
    private static final String RES_ACCOUNT = "com.qoder.payment.facade.account.AccountFacade";
    private static final String RES_ACCOUNTING = "com.qoder.payment.facade.accounting.AccountingFacade";

    /**
     * I-7 Dubbo 消费端熔断守卫（受理前创建 entry）。
     * <p>Dubbo 3.2 的 "No provider available" 在 Directory 层抛出、绕过 sentinel-apache-dubbo-adapter
     * 过滤链，故在网关调用边界显式埋点：异常经 Tracer 计入接口级熔断统计；熔断开 → B0003 规范错误体，
     * 拒绝发生在 RPC 发起前（不启动下游 TCC，无在途悬挂单）。adapter 仍覆盖在途 RPC 异常/慢调用。
     * <p>Sentinel 自身故障（entry 创建异常）→ fail-open 直接调用，不阻断支付。
     */
    private static <T> T rpcGuard(String resource, Supplier<T> call) {
        Entry entry;
        try {
            entry = SphU.entry(resource, ResourceTypeConstants.COMMON_RPC, EntryType.OUT);
        } catch (BlockException be) {
            MetricsSupport.count("sentinel_block", "type", be.getClass().getSimpleName(), "resource", resource);
            throw SentinelConfig.toBizException(be);
        } catch (Exception | LinkageError t) {
            return call.get();
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            // 仅 RPC 级故障（No provider/超时/网络）计入熔断统计；业务/参数异常不计
            if (e instanceof org.apache.dubbo.rpc.RpcException) {
                try {
                    Tracer.traceEntry(e, entry);
                } catch (Exception | LinkageError ignore) {
                    // 统计失败不影响业务异常传播
                }
            }
            throw e;
        } finally {
            try {
                entry.exit();
            } catch (Exception | LinkageError ignore) {
                // ignore
            }
        }
    }

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private AccountFacade accountFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    @Autowired
    private MerchantCache merchantCache;

    @Autowired
    private NonceStore nonceStore;

    @PostMapping("/trade.create")
    public ResponseEntity<Object> tradeCreate(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = verify(body, "trade.create");
        OrderCreateRequest request = new OrderCreateRequest();
        request.setMerchantId(merchant.getId());
        request.setSubject(str(body, "subject"));
        request.setAmount(longOf(body.get("amount")));
        request.setOutTradeNo(body.get("outTradeNo") == null ? null : body.get("outTradeNo").toString());
        return GatewaySupport.of(rpcGuard(RES_TRADE, () -> tradeFacade.createOrder(request)));
    }

    @PostMapping("/trade.pay")
    public ResponseEntity<Object> tradePay(@RequestBody Map<String, Object> body) {
        verify(body, "trade.pay");
        PayRequest request = new PayRequest();
        request.setOrderId(str(body, "orderId"));
        request.setMethod(ApiController.normalizeMethod(str(body, "method")));
        request.setAccountId(body.get("accountId") == null ? null : body.get("accountId").toString());
        request.setCardNo(body.get("cardNo") == null ? null : body.get("cardNo").toString());
        request.setRequestId(str(body, "outTradeNo"));
        request.setCallbackUrl(body.get("callbackUrl") == null ? null : body.get("callbackUrl").toString());
        return GatewaySupport.of(rpcGuard(RES_PAYMENT, () -> paymentFacade.pay(request)));
    }

    /** 商户侧用户/司机开户（name 幂等）：返回会员与账户 ID，供调用方持久化映射。 */
    @PostMapping("/account.open")
    public ResponseEntity<Object> accountOpen(@RequestBody Map<String, Object> body) {
        verify(body, "account.open");
        String name = str(body, "name");
        String type = body.get("type") == null ? "USER" : body.get("type").toString();
        MemberDTO member = rpcGuard(RES_MEMBER, () -> memberFacade.list()).orThrow().stream()
                .filter(m -> name.equals(m.getName()) && type.equals(m.getType()))
                .findFirst().orElse(null);
        if (member == null) {
            MemberCreateRequest request = new MemberCreateRequest();
            request.setName(name);
            request.setType(type);
            member = rpcGuard(RES_MEMBER, () -> memberFacade.create(request)).orThrow();
        }
        final MemberDTO resolved = member;
        List<AccountDTO> accounts = rpcGuard(RES_ACCOUNT, () -> accountFacade.list(resolved.getId())).orThrow();
        AccountDTO account = accounts.isEmpty()
                ? rpcGuard(RES_ACCOUNT, () -> accountFacade.create(resolved.getId())).orThrow()
                : accounts.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memberId", member.getId());
        result.put("accountId", account.getId());
        return GatewaySupport.of(Result.ok(result));
    }

    @PostMapping("/account.topup")
    public ResponseEntity<Object> accountTopup(@RequestBody Map<String, Object> body) {
        verify(body, "account.topup");
        TopupRequest request = new TopupRequest();
        request.setAccountId(str(body, "accountId"));
        request.setAmount(longOf(body.get("amount")));
        request.setBizNo(str(body, "bizNo"));
        return GatewaySupport.of(rpcGuard(RES_ACCOUNTING, () -> accountingFacade.topup(request)));
    }

    @PostMapping("/account.transfer")
    public ResponseEntity<Object> accountTransfer(@RequestBody Map<String, Object> body) {
        verify(body, "account.transfer");
        long amount = longOf(body.get("amount"));
        PostRequest request = new PostRequest();
        request.setBizNo(str(body, "bizNo"));
        request.setSubject(body.get("subject") == null ? "商户划转" : body.get("subject").toString());
        request.setLines(List.of(
                line(str(body, "fromAccountId"), "D", amount),
                line(str(body, "toAccountId"), "C", amount)));
        return GatewaySupport.of(rpcGuard(RES_ACCOUNTING, () -> accountingFacade.post(request)));
    }

    /** 提现出金：账户扣款入 SYS_CASH（渠道清算），bizNo 幂等。 */
    @PostMapping("/account.withdraw")
    public ResponseEntity<Object> accountWithdraw(@RequestBody Map<String, Object> body) {
        verify(body, "account.withdraw");
        long amount = longOf(body.get("amount"));
        PostRequest request = new PostRequest();
        request.setBizNo(str(body, "bizNo"));
        request.setSubject("提现出金");
        request.setLines(List.of(
                line(str(body, "accountId"), "D", amount),
                line("SYS_CASH", "C", amount)));
        return GatewaySupport.of(rpcGuard(RES_ACCOUNTING, () -> accountingFacade.post(request)));
    }

    @PostMapping("/account.balance")
    public ResponseEntity<Object> accountBalance(@RequestBody Map<String, Object> body) {
        verify(body, "account.balance");
        return GatewaySupport.of(rpcGuard(RES_ACCOUNTING, () -> accountingFacade.balance(str(body, "accountId"))));
    }

    /** 充值资金单：交易单+支付单（SYS_FUND，无手续费），bizNo 幂等，返回支付单。 */
    @PostMapping("/fund.topup")
    public ResponseEntity<Object> fundTopup(@RequestBody Map<String, Object> body) {
        verify(body, "fund.topup");
        return GatewaySupport.of(rpcGuard(RES_PAYMENT, () -> paymentFacade.fundPay(fundRequest(body, "TOPUP"))));
    }

    /** 提现资金单：交易单+支付单（SYS_FUND，无手续费），bizNo 幂等，返回支付单。 */
    @PostMapping("/fund.withdraw")
    public ResponseEntity<Object> fundWithdraw(@RequestBody Map<String, Object> body) {
        verify(body, "fund.withdraw");
        return GatewaySupport.of(rpcGuard(RES_PAYMENT, () -> paymentFacade.fundPay(fundRequest(body, "WITHDRAW"))));
    }

    private static FundPayRequest fundRequest(Map<String, Object> body, String bizType) {
        FundPayRequest request = new FundPayRequest();
        request.setBizType(bizType);
        request.setAccountId(str(body, "accountId"));
        request.setAmount(longOf(body.get("amount")));
        request.setRequestId(str(body, "bizNo"));
        request.setCallbackUrl(body.get("callbackUrl") == null ? null : body.get("callbackUrl").toString());
        return request;
    }

    @PostMapping("/trade.refund")
    public ResponseEntity<Object> tradeRefund(@RequestBody Map<String, Object> body) {
        verify(body, "trade.refund");
        RefundRequest request = new RefundRequest();
        request.setAmount(longOf(body.get("amount")));
        request.setRequestId(str(body, "outRefundNo"));
        return GatewaySupport.of(rpcGuard(RES_PAYMENT, () -> paymentFacade.refund(str(body, "paymentId"), request)));
    }

    /** 订单+支付单查询：商户对账 PULL 补查。 */
    @PostMapping("/trade.query")
    public ResponseEntity<Object> tradeQuery(@RequestBody Map<String, Object> body) {
        verify(body, "trade.query");
        OrderDTO order = rpcGuard(RES_TRADE, () -> tradeFacade.getOrder(str(body, "orderId"))).orThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        if (order.getPaymentId() != null) {
            result.put("payment", rpcGuard(RES_PAYMENT, () -> paymentFacade.getPayment(order.getPaymentId())).orThrow());
        }
        return GatewaySupport.of(Result.ok(result));
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    /**
   * N-4 additive：供同包新增 OpenAPI 控制器（{@link OpenApiNotifyController}）复用<b>同一套</b>验签链，
   * 不复制 canonical 算法、不改既有 {@link #verify} 一个字节（八个冻结方法行为不变）。
   */
    MemberDTO verifyFor(Map<String, Object> body, String endpoint) {
        return verify(body, endpoint);
    }

    /**
   * 验签 + I-7 治理守卫（全部发生在业务受理之前，拒绝不落 PROCESSING 支付单）：
   * ① timestamp ±300s 窗（既有）；② HMAC/RSA2 验签（既有，canonical 逐字节不变）；
   * ③ appId 热点参数 QPS 限流（验签通过后，防伪造 appId 耗他人配额）→ 429 A0429；
   * ④ 可选 nonce 防重放（不传则完全跳过——ride-pro 兼容红线，行为逐字节不变）→ 409 A0409。
   */
    private MemberDTO verify(Map<String, Object> body, String endpoint) {
        String appId = str(body, "appId");
        String sign = str(body, "sign");
        long timestamp;
        try {
            timestamp = Long.parseLong(String.valueOf(body.get("timestamp")));
        } catch (Exception e) {
            throw new BizException("A0400", "timestamp 无效");
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            throw new BizException("A0400", "timestamp 超出 5 分钟有效期");
        }
        MemberDTO merchant = merchantCache.get(appId, () -> rpcGuard(RES_MEMBER, () -> memberFacade.getByAppId(appId)).orThrow());
        Map<String, Object> params = new TreeMap<>(body);
        params.remove("sign");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if ("RSA2".equalsIgnoreCase(String.valueOf(body.get("sign_type")))) {
            // RSA2（M-1/S-1）：商户证书公钥 SHA256withRSA；未上传证书时明确报错，不静默降级。
            if (merchant.getCertPublicKey() == null || merchant.getCertPublicKey().isBlank()) {
                throw new BizException("A0400", "RSA2 证书缺失，请先上传商户证书");
            }
            if (!rsaVerify(merchant.getCertPublicKey(), sb.toString(), sign)) {
                log.warn("OpenAPI RSA2 验签失败 appId={}", appId);
                throw new BizException("A0400", "签名验证失败");
            }
        } else {
            // HMAC 多密钥并存（M-1）：先 ACTIVE，再宽限期内 RETIRED；canonical 与冻结算法逐字节一致。
            boolean ok = merchant.getAppSecret() != null && hmac(merchant.getAppSecret(), sb.toString()).equals(sign);
            if (!ok && merchant.getGraceSecrets() != null) {
                long now = System.currentTimeMillis();
                List<String> graceSecrets = merchant.getGraceSecrets();
                List<Long> deadlines = merchant.getGraceDeadlines();
                for (int i = 0; i < graceSecrets.size(); i++) {
                    // 验签时点二次校验宽限截止：商户缓存 60s TTL 内到期也即时拒绝
                    if (deadlines != null && i < deadlines.size() && deadlines.get(i) != null && deadlines.get(i) <= now) {
                        continue;
                    }
                    String grace = graceSecrets.get(i);
                    if (grace != null && hmac(grace, sb.toString()).equals(sign)) {
                        ok = true;
                        log.info("OpenAPI 宽限期旧密钥验签通过 appId={}", appId);
                        break;
                    }
                }
            }
            if (!ok) {
                log.warn("OpenAPI 验签失败 appId={}", appId);
                throw new BizException("A0400", "签名验证失败");
            }
        }
        // I-7 ③：appId 热点参数限流（openapi:<endpoint> 资源，ParamFlowRule paramIdx=0）。
        // Sentinel 故障 fail-open 放行；被拒 → 429，发生在任何 facade 调用之前。
        String guardResource = "openapi:" + endpoint;
        if (!SentinelGuard.tryPass(guardResource, appId)) {
            MetricsSupport.count("openapi_rate_reject", "resource", guardResource);
            log.warn("OpenAPI 限流拒绝 appId={} endpoint={}", appId, endpoint);
            throw new BizException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage());
        }
        // I-7 ④：可选 nonce 防重放（与 timestamp 窗互补，TTL 300s）。
        // 红线：nonce 为可选 additive 参数——不传时跳过，ride-pro 既有调用行为逐字节不变。
        Object nonceValue = body.get("nonce");
        if (nonceValue != null && !nonceValue.toString().isBlank()) {
            String nonce = nonceValue.toString().trim();
            if (nonce.length() > 64) {
                throw new BizException(ResultCode.PARAM_ERROR.getCode(), "nonce 超长（最多 64 字符）");
            }
            if (!nonceStore.firstUse(appId, nonce)) {
                MetricsSupport.count("openapi_replay_reject");
                log.warn("OpenAPI 重放拒绝 appId={} endpoint={} nonce={}", appId, endpoint, nonce);
                throw new BizException(ResultCode.CONFLICT.getCode(), "重复请求：nonce 已使用");
            }
        }
        log.info("OpenAPI 验签通过 appId={} merchantId={}", appId, merchant.getId());
        return merchant;
    }

    private static boolean rsaVerify(String publicKeyBase64, String data, String signBase64) {
        try {
            java.security.PublicKey key = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(
                            java.util.Base64.getDecoder().decode(publicKeyBase64)));
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(java.util.Base64.getDecoder().decode(signBase64));
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return v.toString();
    }

    private static Long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new BizException("A0400", "amount 无效");
        }
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("B0002", "签名计算失败");
        }
    }
}