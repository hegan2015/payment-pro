package com.qoder.payment.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * I-8 指标自动装配：凡依赖 payment-common 且 classpath 含 Micrometer 的应用
 * （13 域中除 registry 外的 12 个进程）启动即绑定指标域并预注册业务计数器。
 * 经 META-INF/spring/...AutoConfiguration.imports 生效，无需各域组件扫描。
 */
@AutoConfiguration(afterName =
        "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration")
@ConditionalOnClass(MeterRegistry.class)
public class MetricsAutoConfiguration {

    @Bean
    public MetricsBootstrap paymentMetricsBootstrap(ObjectProvider<MeterRegistry> registries,
                                                    Environment environment) {
        return new MetricsBootstrap(registries, environment);
    }
}