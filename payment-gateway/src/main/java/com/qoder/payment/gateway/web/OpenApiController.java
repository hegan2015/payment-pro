package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
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

/** OpenAPI：商户 appId + HMAC-SHA256 签名接入，对标支付宝开放平台网关。 */
@Slf4j
@RestController
@RequestMapping("/openapi")
public class OpenApiController {

    private static final long TIMESTAMP_TOLERANCE_MS = 300_000L;

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

    @PostMapping("/trade.create")
    public ResponseEntity<Object> tradeCreate(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = verify(body);
        OrderCreateRequest request = new OrderCreateRequest();
        request.setMerchantId(merchant.getId());
        request.setSubject(str(body, "subject"));
        request.setAmount(longOf(body.get("amount")));
        return GatewaySupport.of(tradeFacade.createOrder(request));
    }

    @PostMapping("/trade.pay")
    public ResponseEntity<Object> tradePay(@RequestBody Map<String, Object> body) {
        verify(body);
        PayRequest request = new PayRequest();
        request.setOrderId(str(body, "orderId"));
        request.setMethod(ApiController.normalizeMethod(str(body, "method")));
        request.setAccountId(body.get("accountId") == null ? null : body.get("accountId").toString());
        request.setCardNo(body.get("cardNo") == null ? null : body.get("cardNo").toString());
        request.setRequestId(str(body, "outTradeNo"));
        request.setCallbackUrl(body.get("callbackUrl") == null ? null : body.get("callbackUrl").toString());
        return GatewaySupport.of(paymentFacade.pay(request));
    }

    /** 商户侧用户/司机开户（name 幂等）：返回会员与账户 ID，供调用方持久化映射。 */
    @PostMapping("/account.open")
    public ResponseEntity<Object> accountOpen(@RequestBody Map<String, Object> body) {
        verify(body);
        String name = str(body, "name");
        String type = body.get("type") == null ? "USER" : body.get("type").toString();
        MemberDTO member = memberFacade.list().orThrow().stream()
                .filter(m -> name.equals(m.getName()) && type.equals(m.getType()))
                .findFirst().orElse(null);
        if (member == null) {
            MemberCreateRequest request = new MemberCreateRequest();
            request.setName(name);
            request.setType(type);
            member = memberFacade.create(request).orThrow();
        }
        List<AccountDTO> accounts = accountFacade.list(member.getId()).orThrow();
        AccountDTO account = accounts.isEmpty()
                ? accountFacade.create(member.getId()).orThrow()
                : accounts.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memberId", member.getId());
        result.put("accountId", account.getId());
        return GatewaySupport.of(Result.ok(result));
    }

    @PostMapping("/account.topup")
    public ResponseEntity<Object> accountTopup(@RequestBody Map<String, Object> body) {
        verify(body);
        TopupRequest request = new TopupRequest();
        request.setAccountId(str(body, "accountId"));
        request.setAmount(longOf(body.get("amount")));
        request.setBizNo(str(body, "bizNo"));
        return GatewaySupport.of(accountingFacade.topup(request));
    }

    @PostMapping("/account.transfer")
    public ResponseEntity<Object> accountTransfer(@RequestBody Map<String, Object> body) {
        verify(body);
        long amount = longOf(body.get("amount"));
        PostRequest request = new PostRequest();
        request.setBizNo(str(body, "bizNo"));
        request.setSubject(body.get("subject") == null ? "商户划转" : body.get("subject").toString());
        request.setLines(List.of(
                line(str(body, "fromAccountId"), "D", amount),
                line(str(body, "toAccountId"), "C", amount)));
        return GatewaySupport.of(accountingFacade.post(request));
    }

    /** 提现出金：账户扣款入 SYS_CASH（渠道清算），bizNo 幂等。 */
    @PostMapping("/account.withdraw")
    public ResponseEntity<Object> accountWithdraw(@RequestBody Map<String, Object> body) {
        verify(body);
        long amount = longOf(body.get("amount"));
        PostRequest request = new PostRequest();
        request.setBizNo(str(body, "bizNo"));
        request.setSubject("提现出金");
        request.setLines(List.of(
                line(str(body, "accountId"), "D", amount),
                line("SYS_CASH", "C", amount)));
        return GatewaySupport.of(accountingFacade.post(request));
    }

    @PostMapping("/account.balance")
    public ResponseEntity<Object> accountBalance(@RequestBody Map<String, Object> body) {
        verify(body);
        return GatewaySupport.of(accountingFacade.balance(str(body, "accountId")));
    }

    @PostMapping("/trade.refund")
    public ResponseEntity<Object> tradeRefund(@RequestBody Map<String, Object> body) {
        verify(body);
        RefundRequest request = new RefundRequest();
        request.setAmount(longOf(body.get("amount")));
        request.setRequestId(str(body, "outRefundNo"));
        return GatewaySupport.of(paymentFacade.refund(str(body, "paymentId"), request));
    }

    /** 订单+支付单查询：商户对账 PULL 补查。 */
    @PostMapping("/trade.query")
    public ResponseEntity<Object> tradeQuery(@RequestBody Map<String, Object> body) {
        verify(body);
        OrderDTO order = tradeFacade.getOrder(str(body, "orderId")).orThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        if (order.getPaymentId() != null) {
            result.put("payment", paymentFacade.getPayment(order.getPaymentId()).orThrow());
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

    private MemberDTO verify(Map<String, Object> body) {
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
        MemberDTO merchant = memberFacade.getByAppId(appId).orThrow();
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
        if (!hmac(merchant.getAppSecret(), sb.toString()).equals(sign)) {
            log.warn("OpenAPI 验签失败 appId={}", appId);
            throw new BizException("A0400", "签名验证失败");
        }
        log.info("OpenAPI 验签通过 appId={} merchantId={}", appId, merchant.getId());
        return merchant;
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