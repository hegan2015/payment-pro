package com.qoder.payment.notify.service;

import com.qoder.payment.common.secret.SecretProvider;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 通知签名（S-3/N-1）：签名内容 = 通知 body + "\n" + timestamp + "\n" + nonce。
 * <ul>
 *   <li>默认 HMAC-SHA256：商户 appSecret（经 {@link MemberFacade#getByAppId} 内部接口取，不依赖脱敏的 list）；
 *       无 appId 的系统自动通知用平台共享密钥（{@link SecretProvider}，local 文件惰性生成）。</li>
 *   <li>可选 RSA2：平台私钥（{@link SecretProvider#getPrivateKey}）SHA256withRSA；
 *       local 无证书/私钥时降级 HMAC 并把 signType 标记为 HMAC_SHA256。</li>
 * </ul>
 * 密钥缺失/取密钥失败抛 {@link SignException}，由 {@link NotifyPusher} 留 PENDING 退避不丢 + 告警。
 * 推送头：X-Payment-Signature / X-Payment-Timestamp / X-Payment-Nonce（另附 X-Payment-Sign-Type）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifySigner {

    public static final String HMAC_SHA256 = "HMAC_SHA256";
    public static final String RSA2 = "RSA2";

    private final SecretProvider secretProvider;

    @DubboReference(check = false)
    private MemberFacade memberFacade;

    @Value("${payment.notify.sign.platform-secret-ref:platform-notify}")
    private String platformSecretRef;

    @Value("${payment.notify.sign.platform-key-ref:platform-notify-rsa}")
    private String platformKeyRef;

    /** 签名要素：实际算法 + 签名值 + 时间戳 + 随机 nonce。 */
    public record SignResult(String signType, String signature, String timestamp, String nonce) {
    }

    /** 签名失败/密钥缺失（推送方据此留 PENDING 退避，不丢通知）。 */
    public static class SignException extends RuntimeException {
        public SignException(String message) {
            super(message);
        }
    }

    public SignResult sign(NotifyRecordDO record) {
        String desired = record.getSignType() == null || record.getSignType().isBlank()
                ? HMAC_SHA256 : record.getSignType();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = Long.toString(System.currentTimeMillis());
        String content = canonical(record.getPayload(), timestamp, nonce);

        if (RSA2.equalsIgnoreCase(desired)) {
            PrivateKey privateKey = secretProvider.getPrivateKey(platformKeyRef);
            if (privateKey != null) {
                return new SignResult(RSA2, rsa2(privateKey, content), timestamp, nonce);
            }
            log.warn("RSA2 平台私钥缺失 ref={}，降级 HMAC notifyId={}", platformKeyRef, record.getId());
        }

        String secret = resolveHmacSecret(record.getAppId());
        if (secret == null || secret.isBlank()) {
            throw new SignException("商户密钥缺失 appId=" + record.getAppId());
        }
        return new SignResult(HMAC_SHA256, hmac(secret, content), timestamp, nonce);
    }

    /** 商户验签规范：对收到的原始 body 与头 timestamp/nonce 按此式重算 HMAC 比对。 */
    public static String canonical(String body, String timestamp, String nonce) {
        return (body == null ? "" : body) + "\n" + timestamp + "\n" + nonce;
    }

    private String resolveHmacSecret(String appId) {
        if (appId != null && !appId.isBlank()) {
            try {
                MemberDTO merchant = memberFacade.getByAppId(appId).orThrow();
                return merchant == null ? null : merchant.getAppSecret();
            } catch (Exception e) {
                log.warn("取商户密钥失败 appId={}: {}", appId, e.getMessage());
                return null;
            }
        }
        // 无 appId（系统自动通知）→ 平台共享密钥，local 文件缺失时惰性生成，保证零依赖送达。
        return secretProvider.getSecret(platformSecretRef);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SignException("HMAC 签名计算失败: " + e.getMessage());
        }
    }

    private static String rsa2(PrivateKey privateKey, String data) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new SignException("RSA2 签名计算失败: " + e.getMessage());
        }
    }
}