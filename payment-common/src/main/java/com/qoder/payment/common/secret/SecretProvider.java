package com.qoder.payment.common.secret;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * 密钥管理 SPI（S-1 地基）：密钥与引用分离，DB/配置只存 ref，明文密钥落在 Vault/本地文件。
 * <p>双 profile：local 走 {@link LocalFileSecretProvider}（零依赖文件 keystore）；
 * docker 可启用 {@link VaultSecretProvider}（{@code payment.secret.vault.enabled=true}，当前为桩）。
 * <p>安全约束：实现严禁把密钥明文写入日志；不可达/缺失时验签侧应 fail-fast（安全优先）。
 * 轮换 {@link #rotate(String)} 为 M-1 预留，本轮不实现。
 */
public interface SecretProvider {

    /** 取对称密钥明文（如商户 appSecret / 平台共享 HMAC 密钥）；不存在返回 {@code null}。 */
    String getSecret(String ref);

    /** 取平台 RSA 私钥（RSA2 签名用）；不存在返回 {@code null}（调用方可降级 HMAC）。 */
    PrivateKey getPrivateKey(String ref);

    /** 取 X509 证书（RSA2 验签 / 到期告警用）；不存在返回 {@code null}。 */
    X509Certificate getCert(String ref);

    /** 密钥轮换（M-1 预留，本轮不实现）。 */
    default String rotate(String ref) {
        throw new UnsupportedOperationException("密钥轮换属 M-1，S-1 最小版未实现");
    }
}