package com.qoder.payment.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * DynamicConfig 自动装配（I-2，引入 payment-common 即生效，跨域基础设施 §0.6）。
 * <p>双 profile 开关 {@code payment.config.apollo.enabled}（环境变量
 * {@code PAYMENT_CONFIG_APOLLO_ENABLED} 经 Spring relaxed binding 同效）：
 * <ul>
 *   <li>{@code true} → {@link ApolloDynamicConfig}（apollo-client，异步初始化，
 *       不可达降级 last-known-good + 默认值，绝不阻断支付）；</li>
 *   <li>{@code false}/未设置（local 默认）→ {@link LocalFileDynamicConfig}
 *       （{@code ~/.config/payment-pro/config.properties} + 30s reload，零依赖，
 *       不加载 Apollo Bean、不建任何配置中心连接）。</li>
 * </ul>
 * 接入方可用自定义 {@link DynamicConfig} Bean 覆盖（{@link ConditionalOnMissingBean}）。
 */
@AutoConfiguration
public class DynamicConfigAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamicConfig.class)
    @ConditionalOnProperty(name = "payment.config.apollo.enabled", havingValue = "true")
    public DynamicConfig apolloDynamicConfig(Environment env) {
        String meta = env.getProperty("payment.config.apollo.meta");
        if (meta == null || meta.isBlank()) {
            meta = System.getenv("APOLLO_META");
        }
        return new ApolloDynamicConfig(
                env.getProperty("payment.config.apollo.app-id", "payment-pro"),
                meta == null ? "http://127.0.0.1:18070" : meta,
                env.getProperty("payment.config.apollo.env", "DEV"),
                env.getProperty("payment.config.apollo.cluster", "default"),
                env.getProperty("payment.config.apollo.namespaces", ApolloDynamicConfig.DEFAULT_NAMESPACES));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamicConfig.class)
    @ConditionalOnProperty(name = "payment.config.apollo.enabled", havingValue = "false", matchIfMissing = true)
    public DynamicConfig localFileDynamicConfig(Environment env) {
        String path = env.getProperty("payment.config.file");
        return path == null || path.isBlank()
                ? new LocalFileDynamicConfig()
                : new LocalFileDynamicConfig(Path.of(path));
    }
}