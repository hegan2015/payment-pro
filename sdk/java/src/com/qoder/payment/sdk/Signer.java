package com.qoder.payment.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * G-4b Java SDK 签名工具：与服务端冻结 canonical <b>逐字节一致</b>。
 *
 * <p>TreeMap 排序 k=v&（剔除 sign、跳过 null）+ HMAC-SHA256 小写 hex + 毫秒 timestamp + 可选 nonce；
 * RSA2 = SHA256withRSA + Base64（PKCS#8 私钥）。
 */
public final class Signer {

    private Signer() {
    }

    public static String canonical(Map<String, Object> params) {
        Map<String, Object> sorted = new TreeMap<>(params);
        sorted.remove("sign");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 补公共参数并签名（appId 需已在 params 中）。nonce 传 null 即不携带（兼容红线）。 */
    public static Map<String, Object> sign(Map<String, Object> params, String appSecret, String nonce) {
        Map<String, Object> body = new TreeMap<>(params);
        body.put("timestamp", System.currentTimeMillis());
        if (nonce != null && !nonce.isBlank()) {
            body.put("nonce", nonce);
        }
        body.put("sign", hmac(appSecret, canonical(body)));
        return body;
    }

    public static Map<String, Object> signRsa2(Map<String, Object> params, String pkcs8PrivateKeyBase64, String nonce) {
        try {
            Map<String, Object> body = new TreeMap<>(params);
            body.put("timestamp", System.currentTimeMillis());
            body.put("sign_type", "RSA2");
            if (nonce != null && !nonce.isBlank()) {
                body.put("nonce", nonce);
            }
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8PrivateKeyBase64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(canonical(body).getBytes(StandardCharsets.UTF_8));
            body.put("sign", Base64.getEncoder().encodeToString(signature.sign()));
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("RSA2 签名失败", e);
        }
    }
}