package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.cashier.CashierDTO;
import com.qoder.payment.facade.cashier.CashierFacade;
import com.qoder.payment.facade.cashier.CreateCodeRequest;
import com.qoder.payment.facade.cashier.PayCodeDTO;
import com.qoder.payment.facade.cashier.PayCodeFacade;
import com.qoder.payment.facade.cashier.ScanPayRequest;
import com.qoder.payment.facade.cashier.SetPayPasswordRequest;
import com.qoder.payment.facade.cashier.VerifiedPayRequest;
import com.qoder.payment.facade.cashier.VerifyRequest;
import com.qoder.payment.facade.cashier.VerifyResultDTO;
import com.qoder.payment.facade.payment.PayResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收银台开放能力（C-1/C-2/C-3 → 商户 OpenAPI，<b>全部 additive 新方法</b>）。
 *
 * <p><b>定位</b>：payment-pro <b>不再自带 C 端 H5</b>（原型移至 {@code ui-design/h5-prototype/}，
 * 服务路径 /h5/** 已摘除）。C 端收银台由对接方（ride-pro 打车系统）自建，
 * 经本控制器以商户身份（appId + HMAC-SHA256）调用收银台能力。
 *
 * <p><b>验签</b>：复用 {@link OpenApiController#verifyFor} 的<b>同一套</b>链路——
 * canonical（TreeMap 排序 {@code k=v&}、剔除 sign/null）逐字节不变、timestamp <b>毫秒</b> ±300s、
 * HMAC-SHA256（ACTIVE + 宽限期 RETIRED）/RSA2、appId 热点参数限流（I-7，资源 {@code openapi:<method>}）、
 * 可选 nonce 防重放（TTL 300s）。<b>全部拒绝发生在业务受理之前</b>，不落 PROCESSING 悬挂单。
 *
 * <p><b>ride 兼容红线（§0.7）</b>：既有 11 个商户方法与 canonical 一字不改；本类只新增 9 个
 * {@code cashier.*} 方法，映射既有 cashier 域 facade（不新增 facade 方法、不改域业务逻辑）；
 * BALANCE 仍走同步受理→PROCESSING→SUCCESS，手续费口径不变，requestId 幂等语义不变。
 *
 * <p><b>返回结构</b>：与控制台面 {@code /api/cashier/*} 一致（{@link GatewaySupport} 裸数据，
 * 成功不套 envelope），错误体 {@code {error, code, traceId}} 沿用既有码体系。
 *
 * <p><b>脱敏（E-1）</b>：响应永不含 appSecret/certPublicKey/支付口令；银行卡号<b>只保留后四位</b>
 * （上游 DTO 已掩前 6 后 4，此处把 BIN 一并抹除 → {@code **********0202}）；
 * 短信验证码默认掩码，仅显式 {@code sandbox=true} 联调时返回明文。
 *
 * <p>契约文档：{@code docs/cashier-openapi.md}。
 */
@Slf4j
@RestController
@RequestMapping("/openapi")
@Tag(name = "商户收银台开放能力 (C-1/C-2/C-3)",
        description = "HMAC 验签，免 Bearer；9 个 cashier.* 方法映射控制台 /api/cashier/* 能力，返回裸数据")
public class OpenApiCashierController {

    @DubboReference
    private CashierFacade cashierFacade;

    @DubboReference
    private PayCodeFacade payCodeFacade;

    /** 复用既有验签链（同包包内入口），不复制 canonical 算法、不改 OpenApiController 本体。 */
    @Autowired
    private OpenApiController openApiController;

    // ------------------------------------------------------------------
    // C-1 多支付方式渲染
    // ------------------------------------------------------------------

    /** cashier.methods ← GET /api/cashier/methods（accountId 可选，缺省回退聚合渲染）。 */
    @PostMapping("/cashier.methods")
    @Operation(summary = "cashier.methods 收银台支付方式渲染",
            description = "入参：appId/sign/timestamp(+可选 nonce) + orderId + 可选 accountId。"
                    + "返回 CashierDTO{order,methods[],merchantName,discount,actualAmount,freeVerifyLimit,currency}（裸数据）。")
    public ResponseEntity<Object> methods(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.methods");
        String orderId = required(body, "orderId");
        String accountId = opt(body, "accountId");
        Result<CashierDTO> result = accountId == null
                ? cashierFacade.cashier(orderId)
                : cashierFacade.cashierForPayer(orderId, accountId);
        return GatewaySupport.of(result);
    }

    // ------------------------------------------------------------------
    // C-2 付款码 / 扫码
    // ------------------------------------------------------------------

    /** cashier.code.create ← POST /api/cashier/code（被扫 USER_PRESENTED / 主扫 MERCHANT_PRESENTED）。 */
    @PostMapping("/cashier.code.create")
    @Operation(summary = "cashier.code.create 生成付款码/收款码（沙箱）",
            description = "入参：type(USER_PRESENTED|MERCHANT_PRESENTED) + accountId/orderId（二选一必填）"
                    + " + 可选 amount(分)/requestId(幂等)。返回 PayCodeDTO{code,type,accountId,orderId,amount,status,expireAt,ttlSeconds,...}。")
    public ResponseEntity<Object> createCode(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.code.create");
        CreateCodeRequest request = new CreateCodeRequest();
        request.setType(required(body, "type"));
        request.setAccountId(opt(body, "accountId"));
        request.setOrderId(opt(body, "orderId"));
        request.setAmount(longOpt(body, "amount"));
        request.setRequestId(opt(body, "requestId"));
        return GatewaySupport.of(payCodeFacade.createCode(request));
    }

    /** cashier.code.parse ← POST /api/cashier/code/parse（只读，不核销）。 */
    @PostMapping("/cashier.code.parse")
    @Operation(summary = "cashier.code.parse 解析付款码（只读）",
            description = "入参：code。返回 PayCodeDTO；码不存在 404 A0404。")
    public ResponseEntity<Object> parseCode(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.code.parse");
        Result<PayCodeDTO> result = payCodeFacade.parseCode(required(body, "code"));
        return GatewaySupport.of(result);
    }

    /** cashier.code.query ← GET /api/cashier/code/{code}（轮询 ACTIVE→CONSUMED/EXPIRED）。 */
    @PostMapping("/cashier.code.query")
    @Operation(summary = "cashier.code.query 查询码状态（轮询）",
            description = "入参：code。返回 PayCodeDTO（status ACTIVE/CONSUMED/EXPIRED + ttlSeconds）；不存在 404 A0404。")
    public ResponseEntity<Object> queryCode(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.code.query");
        return GatewaySupport.of(payCodeFacade.parseCode(required(body, "code")));
    }

    /** cashier.scanPay ← POST /api/cashier/scan-pay（一次性核销 + 验证前置 + 幂等支付）。 */
    @PostMapping("/cashier.scanPay")
    @Operation(summary = "cashier.scanPay 扫码受理（核销+验证+幂等支付）",
            description = "入参：code + requestId(幂等键) + 主扫必填 payerAccountId + 可选 method(默认 BALANCE)/"
                    + "verifyToken/payPassword/cardNo/callbackUrl。返回 PayResultDTO{payment,repeated}，cardNo 已脱敏。")
    public ResponseEntity<Object> scanPay(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.scanPay");
        ScanPayRequest request = new ScanPayRequest();
        request.setCode(required(body, "code"));
        request.setRequestId(required(body, "requestId"));
        request.setPayerAccountId(opt(body, "payerAccountId"));
        request.setMethod(ApiController.normalizeMethod(opt(body, "method")));
        request.setVerifyToken(opt(body, "verifyToken"));
        request.setPayPassword(opt(body, "payPassword"));
        request.setCardNo(opt(body, "cardNo"));
        request.setCallbackUrl(opt(body, "callbackUrl"));
        Result<PayResultDTO> result = payCodeFacade.scanPay(request);
        maskPayResult(result);
        return GatewaySupport.of(result);
    }

    // ------------------------------------------------------------------
    // C-3 支付验证 / 验证前置受理
    // ------------------------------------------------------------------

    /** cashier.verify ← POST /api/cashier/verify（PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE）。 */
    @PostMapping("/cashier.verify")
    @Operation(summary = "cashier.verify 支付验证（四型）",
            description = "入参：accountId 或 memberId + orderId + amount(分) + verifyType(PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE)"
                    + " + payPassword/smsCode/bioToken 之一 + 可选 requestId(幂等)。"
                    + "返回 VerifyResultDTO{pass,challengeType,verifyToken,expireAt,reason,remainAttempts,lockedUntil,freeLimit}。")
    public ResponseEntity<Object> verify(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.verify");
        VerifyRequest request = new VerifyRequest();
        request.setAccountId(opt(body, "accountId"));
        request.setMemberId(opt(body, "memberId"));
        request.setOrderId(opt(body, "orderId"));
        request.setAmount(longOpt(body, "amount"));
        request.setVerifyType(opt(body, "verifyType"));
        request.setPayPassword(opt(body, "payPassword"));
        request.setSmsCode(opt(body, "smsCode"));
        request.setBioToken(opt(body, "bioToken"));
        request.setRequestId(opt(body, "requestId"));
        Result<VerifyResultDTO> result = cashierFacade.verify(request);
        return GatewaySupport.of(result);
    }

    /** cashier.payVerified ← POST /api/cashier/pay-verified（验证衔接，BALANCE 同步语义不变）。 */
    @PostMapping("/cashier.payVerified")
    @Operation(summary = "cashier.payVerified 验证前置受理（幂等）",
            description = "入参：orderId + requestId(幂等键) + method(默认 BALANCE) + accountId + verifyToken 或 payPassword"
                    + "（小额免密可空）+ 可选 cardNo/callbackUrl。返回 PayResultDTO{payment,repeated}，cardNo 已脱敏。"
                    + "验证无效/超免密未验证 → 403 A0403，不受理不扣款。")
    public ResponseEntity<Object> payVerified(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.payVerified");
        VerifiedPayRequest request = new VerifiedPayRequest();
        request.setOrderId(required(body, "orderId"));
        request.setRequestId(required(body, "requestId"));
        request.setMethod(ApiController.normalizeMethod(opt(body, "method")));
        request.setAccountId(opt(body, "accountId"));
        request.setCardNo(opt(body, "cardNo"));
        request.setCallbackUrl(opt(body, "callbackUrl"));
        request.setVerifyToken(opt(body, "verifyToken"));
        request.setPayPassword(opt(body, "payPassword"));
        Result<PayResultDTO> result = cashierFacade.payVerified(request);
        maskPayResult(result);
        return GatewaySupport.of(result);
    }

    /** cashier.payPassword.set ← POST /api/cashier/pay-password（沙箱，哈希加盐；明文不落库/不回显）。 */
    @PostMapping("/cashier.payPassword.set")
    @Operation(summary = "cashier.payPassword.set 设置/重置支付口令",
            description = "入参：accountId 或 memberId + password(6 位数字) + 可选 oldPassword(重置校验)。"
                    + "返回裸 boolean true；口令明文永不回显。旧口令错误 403 A0403。")
    public ResponseEntity<Object> setPayPassword(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.payPassword.set");
        SetPayPasswordRequest request = new SetPayPasswordRequest();
        request.setAccountId(opt(body, "accountId"));
        request.setMemberId(opt(body, "memberId"));
        request.setPassword(required(body, "password"));
        request.setOldPassword(opt(body, "oldPassword"));
        Result<Boolean> result = cashierFacade.setPayPassword(request);
        return GatewaySupport.of(result);
    }

    /**
     * cashier.sms.send ← POST /api/cashier/sms/send（沙箱）。
     * <p>默认掩码 smsCode（{@code ******} + {@code smsCodeMasked=true}）：商户服务端不应拿到用户验证码。
     * 仅显式 {@code sandbox=true}（联调）时返回沙箱明文，与控制台面结构一致。
     */
    @PostMapping("/cashier.sms.send")
    @Operation(summary = "cashier.sms.send 发送短信验证码（沙箱）",
            description = "入参：accountId 或 memberId + 可选 orderId + 可选 sandbox(true 时返回沙箱明文码)。"
                    + "返回 {sandbox,smsCode,expireAt,notice}；默认 smsCode 掩码为 ****** 且 smsCodeMasked=true。")
    public ResponseEntity<Object> sendSms(@RequestBody Map<String, Object> body) {
        openApiController.verifyFor(body, "cashier.sms.send");
        Result<Map<String, Object>> result = cashierFacade.sendSms(
                opt(body, "accountId"), opt(body, "memberId"), opt(body, "orderId"));
        if (result.isSuccess() && result.getData() != null && !boolOpt(body, "sandbox")) {
            Map<String, Object> masked = new LinkedHashMap<>(result.getData());
            masked.put("smsCode", "******");
            masked.put("smsCodeMasked", true);
            masked.put("notice", "验证码经短信网关下发至用户手机，不在响应中返回；沙箱联调可传 sandbox=true 取回明文");
            return GatewaySupport.of(Result.ok(masked));
        }
        return GatewaySupport.of(result);
    }

    // ------------------------------------------------------------------
    // 脱敏与参数工具
    // ------------------------------------------------------------------

    /** E-1：支付单卡号只保留后四位（Dubbo 每次调用返回独立反序列化对象，就地脱敏不影响域内状态）。 */
    private static void maskPayResult(Result<PayResultDTO> result) {
        if (result != null && result.isSuccess() && result.getData() != null
                && result.getData().getPayment() != null) {
            result.getData().getPayment().setCardNo(maskCard(result.getData().getPayment().getCardNo()));
        }
    }

    /** 卡号掩码：保留后四位，其余以 * 覆盖；长度≤4 全掩码。 */
    static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            return cardNo;
        }
        String value = cardNo.trim();
        if (value.length() <= 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new com.qoder.payment.common.exception.BizException("A0400", "缺少参数 " + key);
        }
        return value.toString();
    }

    private static String opt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Long longOpt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new com.qoder.payment.common.exception.BizException("A0400", key + " 无效（须为整数「分」）");
        }
    }

    private static boolean boolOpt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }
}