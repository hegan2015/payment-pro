package com.qoder.payment.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * 指标启动引导：设定指标域 → 确保各 MeterRegistry 挂入 Micrometer 全局复合注册表
 * （Actuator 默认亦会挂载，此处显式兜底且幂等）→ 预注册业务计数器 catalog。
 * 无任何注册表时（未接 actuator）预注册为空操作，local 零依赖不受影响。
 */
public class MetricsBootstrap implements InitializingBean {

    private final ObjectProvider<MeterRegistry> registries;
    private final Environment environment;

    public MetricsBootstrap(ObjectProvider<MeterRegistry> registries, Environment environment) {
        this.registries = registries;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        MetricsSupport.setDomain(environment.getProperty("spring.application.name", "payment"));
        registries.orderedStream().forEach(Metrics::addRegistry);
        MetricsSupport.preRegisterBusinessMetrics();
    }
}