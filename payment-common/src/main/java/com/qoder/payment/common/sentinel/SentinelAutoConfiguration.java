package com.qoder.payment.common.sentinel;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * I-7 Sentinel 规则数据源自动装配（引入 payment-common 且 classpath 含
 * sentinel-core + sentinel-parameter-flow-control 的进程生效，当前仅网关）。
 * 经 META-INF/spring/...AutoConfiguration.imports 追加注册，无需各域组件扫描；
 * 未引入 Sentinel 的域不加载任何 Bean、不建连接（local 零依赖红线）。
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "com.alibaba.csp.sentinel.SphU",
        "com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager"
})
public class SentinelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SentinelRuleLoader.class)
    public SentinelRuleLoader sentinelRuleLoader(Environment environment) {
        return new SentinelRuleLoader(environment);
    }
}