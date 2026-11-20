package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.exception.BizException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * G-4a 新增闸门（沙箱路由 / 注册应用前置验签）使用的签名工具。
 *
 * <p><b>算法与 {@code OpenApiController#verify} 逐字节一致</b>（TreeMap 排序 k=v&、剔除 sign、
 * 跳过 null 值、HMAC-SHA256 小写 hex、RSA2=SHA256withRSA/Base64、timestamp 毫秒 ±300s）。
 * 既有冻结方法的验签代码<b>未被修改一行</b>；本类只是新链路复用同一算法，
 * 保证沙箱与生产签名口径完全一致（SDK 一套签名工具通吃）。
 */
public final class OpenApiSignature {

    public static final long TIMESTAMP_TOLERANCE_MS = 300_000L;

    private OpenApiSignature() {
    }

    /** canonical：TreeMap 排序、剔除 sign、跳过 null、k=v 以 & 连接。 */
    public static String canonical(Map<String, Object> body) {
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
        return sb.toString();
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("B0002", "签名计算失败");
        }
    }

    public static boolean rsaVerify(String publicKeyBase64, String data, String signBase64) {
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

    /** timestamp 毫秒窗校验（与冻结实现同一口径与错误码）。 */
    public static long timestamp(Map<String, Object> body) {
        long timestamp;
        try {
            timestamp = Long.parseLong(String.valueOf(body.get("timestamp")));
        } catch (Exception e) {
            throw new BizException("A0400", "timestamp 无效");
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            throw new BizException("A0400", "timestamp 超出 5 分钟有效期");
        }
        return timestamp;
    }

    /** HMAC 验签（沙箱单密钥；生产多密钥/宽限期仍由冻结链路负责）。 */
    public static void verifyHmac(Map<String, Object> body, String secret) {
        String sign = body.get("sign") == null ? null : body.get("sign").toString();
        String data = canonical(body);
        if (secret == null || sign == null || !hmac(secret, data).equals(sign)) {
            throw new BizException("A0400", "签名验证失败");
        }
    }
}