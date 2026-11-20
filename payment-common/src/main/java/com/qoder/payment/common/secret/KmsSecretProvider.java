package com.qoder.payment.common.secret;

import lombok.extern.slf4j.Slf4j;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * 云 KMS 可插拔实现位（S-1 收尾）。本环境无真实云 KMS（无 AK/凭据），按任务约定做成
 * <b>可插拔接口 + local 降级</b>：{@code payment.secret.kms.enabled=true} 时装配；
 * endpoint/keyId 未配置 → 委托 {@link LocalFileSecretProvider}（零依赖不报错）。
 * <p>接入真实 KMS（阿里云 KMS / AWS KMS / 腾讯云 KMS）时在本类实现
 * Decrypt/GetSecretValue 调用即可，业务侧（member/notify/gateway）只依赖 {@link SecretProvider} SPI，
 * 无需改动；也可用自定义 {@link SecretProvider} Bean 直接覆盖自动装配。
 */
@Slf4j
public class KmsSecretProvider implements SecretProvider {

    private final String endpoint;
    private final String keyId;
    private final LocalFileSecretProvider fallback = new LocalFileSecretProvider();

    public KmsSecretProvider(String endpoint, String keyId) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        if (degraded()) {
            log.warn("KMS 配置缺失（endpoint/keyId 为空），SecretProvider 自动降级本地文件实现（零依赖）");
        }
    }

    public boolean degraded() {
        return endpoint.isBlank() || keyId.isBlank();
    }

    @Override
    public String getSecret(String ref) {
        if (degraded()) {
            return fallback.getSecret(ref);
        }
        throw new IllegalStateException("真实云 KMS 未接入（本环境无云凭据）；请配置降级或实现 KmsSecretProvider");
    }

    @Override
    public PrivateKey getPrivateKey(String ref) {
        if (degraded()) {
            return fallback.getPrivateKey(ref);
        }
        throw new IllegalStateException("真实云 KMS 未接入（本环境无云凭据）");
    }

    @Override
    public X509Certificate getCert(String ref) {
        if (degraded()) {
            return fallback.getCert(ref);
        }
        throw new IllegalStateException("真实云 KMS 未接入（本环境无云凭据）");
    }
}