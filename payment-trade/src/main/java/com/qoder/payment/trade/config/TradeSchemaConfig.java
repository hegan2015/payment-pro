package com.qoder.payment.trade.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * T-3/T-1/T-2/T-4 结构升级装配：以 {@link BeanFactoryPostProcessor} 在任何 DataSource Bean 之前
 * 完成 additive DDL（原单表 + 既有分片表同步补列），保证 I-5b 影子复制/逐列比对零差异。
 *
 * <p>local 零依赖：只连本域 H2（表未建即跳过，交 schema.sql）；docker 只连 pp_trade。
 * 不引入任何新中间件、不加载 ShardingSphere（local {@code payment.sharding.enabled=false}）。
 */
@Configuration(proxyBeanMethods = false)
public class TradeSchemaConfig {

    @Bean
    public static BeanFactoryPostProcessor tradeSchemaUpgrade(Environment env) {
        return beanFactory -> new TradeSchemaUpgrade(env).upgrade();
    }
}