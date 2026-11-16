package com.qoder.payment.gateway.web;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.trade.OrderCreateRequest;
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
        return GatewaySupport.of(paymentFacade.pay(request));
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