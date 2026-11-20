package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.sign.AgentPayRequest;
import com.qoder.payment.facade.sign.SignFacade;
import com.qoder.payment.facade.sign.SignRequest;
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

import java.util.Map;

/**
 * CH-6 签约代扣<b>开放面</b>（P2-F 申请项落地：{@code channel.sign} / {@code channel.agent.pay}）。
 *
 * <p><b>全部 additive 新文件</b>：不改 {@link OpenApiController} 本体（v1 冻结，md5 不变）、
 * 不改 {@code SignController}（控制台面）、不改 channel 域 sign 业务逻辑、<b>不给 SignFacade 新增方法</b>
 * —— 只调既有 {@code signContract/unsignContract/agentPay}。
 *
 * <p><b>验签</b>：复用 {@link OpenApiController#verifyFor}（同包包内入口）——canonical 逐字节不变、
 * timestamp 毫秒 ±300s、HMAC(ACTIVE+宽限期 RETIRED)/RSA2、appId 热点参数限流（资源 {@code openapi:channel.sign}
 * / {@code openapi:channel.agent.pay}）、可选 nonce 防重放。<b>拒绝全部发生在受理之前</b>，不落悬挂扣款单。
 *
 * <p><b>身份</b>：{@code operator} 恒取验签返回的商户 appId 对应标识，<b>不接受 body 冒充</b>；
 * 控制台面才走 X-Step-Up-Token 二次认证（开放面以 HMAC 商户身份为凭）。
 *
 * <p><b>脱敏（E-1）</b>：卡号明文只入参，出参为 {@code SignContractDTO}（cardNoMask 后四位 + cardNoHash SHA-256），
 * 响应永不含明文卡号/密文/appSecret；明文不入日志。
 *
 * <p><b>幂等</b>：{@code requestId} 必填 —— 签约命中 {@code (SIGN,accountId:merchantId:channelCode)} 三元组
 * 或同 requestId 返回既有协议（{@code repeated=true}）；代扣同 requestId 返回首次扣款单（绝不重复扣款），
 * 因此<b>同键重放即查询</b>，可用于轮询终态。
 *
 * <p>契约文档：{@code docs/cashier-openapi.md} §6；多端适配：{@code docs/cashier-multiclient.md}。
 */
@Slf4j
@RestController
@RequestMapping("/openapi")
@Tag(name = "商户签约代扣开放能力 (CH-6)",
        description = "HMAC 验签，免 Bearer；channel.sign 签约/解约 + channel.agent.pay 代扣执行，返回裸数据")
public class OpenApiSignController {

    @DubboReference(check = false, timeout = 35000, retries = 0)
    private SignFacade signFacade;

    /** 复用既有验签链（同包包内入口），不复制 canonical 算法、不改 OpenApiController 本体。 */
    @Autowired
    private OpenApiController openApiController;

    /**
     * channel.sign ← 控制台面 POST /api/channel/sign 与 /api/channel/sign/{signNo}/unsign。
     * action=SIGN（默认）/UNSIGN；SIGN 必填 accountId/merchantId/cardNo/requestId，UNSIGN 必填 signNo/requestId。
     */
    @PostMapping("/channel.sign")
    @Operation(summary = "channel.sign 签约/解约（代扣授权）",
            description = "入参：action(SIGN 默认|UNSIGN) + requestId(幂等键) + SIGN 时 accountId/merchantId/cardNo"
                    + "（可选 channelCode/singleLimit/periodLimit/periodType/effectiveAt/expiredAt/deductDay/"
                    + "maxRetry/retryIntervalMs/callbackUrl/scopeNote）；UNSIGN 时 signNo + 可选 reason。"
                    + "返回 SignContractDTO{signNo,status,cardNoMask,cardNoHash,singleLimit,periodLimit,repeated,...}（裸数据）。")
    public ResponseEntity<Object> sign(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = openApiController.verifyFor(body, "channel.sign");
        String operator = operator(merchant);
        String action = opt(body, "action") == null ? "SIGN" : opt(body, "action").toUpperCase();
        if ("UNSIGN".equals(action)) {
            String signNo = required(body, "signNo");
            String requestId = required(body, "requestId");
            log.info("OpenAPI 解约 appId={} signNo={}", merchant.getAppId(), signNo);
            return GatewaySupport.of(signFacade.unsignContract(signNo, requestId, operator, opt(body, "reason")));
        }
        if (!"SIGN".equals(action)) {
            throw new BizException("A0400", "action 须为 SIGN/UNSIGN");
        }
        SignRequest request = new SignRequest();
        request.setAccountId(required(body, "accountId"));
        request.setMerchantId(required(body, "merchantId"));
        request.setCardNo(required(body, "cardNo"));
        request.setRequestId(required(body, "requestId"));
        request.setChannelCode(opt(body, "channelCode"));
        request.setSingleLimit(longOpt(body, "singleLimit"));
        request.setPeriodLimit(longOpt(body, "periodLimit"));
        request.setPeriodType(opt(body, "periodType"));
        request.setEffectiveAt(longOpt(body, "effectiveAt"));
        request.setExpiredAt(longOpt(body, "expiredAt"));
        request.setDeductDay(intOpt(body, "deductDay"));
        request.setMaxRetry(intOpt(body, "maxRetry"));
        request.setRetryIntervalMs(longOpt(body, "retryIntervalMs"));
        request.setCallbackUrl(opt(body, "callbackUrl"));
        request.setScopeNote(opt(body, "scopeNote"));
        // 事件签名取调用商户密钥；operator 恒为验签商户身份，不接受 body 冒充
        request.setAppId(merchant.getAppId());
        request.setOperator(operator);
        log.info("OpenAPI 签约 appId={} accountId={} merchantId={}（卡号明文只入参，不落日志）",
                merchant.getAppId(), request.getAccountId(), request.getMerchantId());
        return GatewaySupport.of(signFacade.signContract(request));
    }

    /**
     * channel.agent.pay ← 控制台面 POST /api/channel/agent-pay。
     * 凭协议号代扣：授权/额度/有效期/扣款日/商户范围校验全部通过后才建单，拒绝在受理前不落悬挂单。
     */
    @PostMapping("/channel.agent.pay")
    @Operation(summary = "channel.agent.pay 代扣执行（幂等）",
            description = "入参：signNo + amount(分) + requestId(幂等键) + 可选 merchantId/subject/callbackUrl。"
                    + "返回 SignDeductDTO{deductNo,signNo,status,paymentId,orderId,amount,attempts,periodKey,repeated,...}。"
                    + "同 requestId 重放返回首次扣款单（不重复扣款），可据此轮询终态。")
    public ResponseEntity<Object> agentPay(@RequestBody Map<String, Object> body) {
        MemberDTO merchant = openApiController.verifyFor(body, "channel.agent.pay");
        AgentPayRequest request = new AgentPayRequest();
        request.setSignNo(required(body, "signNo"));
        request.setAmount(requiredLong(body, "amount"));
        request.setRequestId(required(body, "requestId"));
        request.setMerchantId(opt(body, "merchantId"));
        request.setSubject(opt(body, "subject"));
        request.setCallbackUrl(opt(body, "callbackUrl"));
        request.setOperator(operator(merchant));
        log.info("OpenAPI 代扣 appId={} signNo={} amount={}", merchant.getAppId(),
                request.getSignNo(), request.getAmount());
        return GatewaySupport.of(signFacade.agentPay(request));
    }

    // ------------------------------------------------------------------
    // 身份与参数工具（与 OpenApiCashierController 同口径）
    // ------------------------------------------------------------------

    /** operator 恒取验签商户身份（appId），绝不接受 body 传入的 operator 冒充他人。 */
    private static String operator(MemberDTO merchant) {
        return "openapi:" + merchant.getAppId();
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value.toString().trim();
    }

    private static String opt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
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
            throw new BizException("A0400", key + " 无效（须为整数「分」）");
        }
    }

    private static long requiredLong(Map<String, Object> body, String key) {
        Long value = longOpt(body, key);
        if (value == null) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value;
    }

    private static Integer intOpt(Map<String, Object> body, String key) {
        Long value = longOpt(body, key);
        return value == null ? null : value.intValue();
    }
}