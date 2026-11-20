package com.qoder.payment.common.secret;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * SecretProvider 自动装配（引入 payment-common 即生效，跨域基础设施 §0.6）。
 * <p>双 profile 开关：
 * <ul>
 *   <li>{@code payment.secret.vault.enabled=true} → {@link VaultSecretProvider}
 *       （KV v2；url/token 配置缺失时内部自动降级 {@link LocalFileSecretProvider}，零依赖不报错）；</li>
 *   <li>{@code payment.secret.kms.enabled=true} → {@link KmsSecretProvider}（可插拔位，未配置降级本地）；</li>
 *   <li>未设置（local 默认）→ {@link LocalFileSecretProvider} 文件密钥。</li>
 * </ul>
 * 接入方可用自定义 {@link SecretProvider} Bean 覆盖（{@link ConditionalOnMissingBean}）。
 */
@AutoConfiguration
public class SecretProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecretProvider.class)
    @ConditionalOnProperty(name = "payment.secret.vault.enabled", havingValue = "true")
    public SecretProvider vaultSecretProvider(Environment env) {
        return new VaultSecretProvider(
                env.getProperty("payment.secret.vault.url", ""),
                env.getProperty("payment.secret.vault.token", ""),
                env.getProperty("payment.secret.vault.mount", "secret"),
                env.getProperty("payment.secret.vault.prefix", "payment"));
    }

    @Bean
    @ConditionalOnMissingBean(SecretProvider.class)
    @ConditionalOnProperty(name = "payment.secret.kms.enabled", havingValue = "true")
    public SecretProvider kmsSecretProvider(Environment env) {
        return new KmsSecretProvider(
                env.getProperty("payment.secret.kms.endpoint", ""),
                env.getProperty("payment.secret.kms.key-id", ""));
    }

    @Bean
    @ConditionalOnMissingBean(SecretProvider.class)
    public SecretProvider localFileSecretProvider() {
        return new LocalFileSecretProvider();
    }
}