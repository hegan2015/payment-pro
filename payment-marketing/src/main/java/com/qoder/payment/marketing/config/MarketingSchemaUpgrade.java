package com.qoder.payment.marketing.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * X-3 营销域建表入口：Bean 初始化阶段执行，<b>早于 Dubbo 服务导出</b>，
 * 杜绝「服务已接流量但表尚未建好」窗口。local H2 / docker MySQL 同一入口、同一 DDL（幂等、只增不改）。
 *
 * <p>本域不分片（{@code payment.sharding.enabled=false} 双 profile），DataSource 即原单库直连，
 * 无需 ShardRawAccess 解包；仍调用 unwrap 以兼容未来接入分片设施。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("X-3 营销域建表失败：JdbcTemplate 无 DataSource");
        }
        MarketingSchema.ensure(com.qoder.payment.common.db.ShardRawAccess.unwrap(ds));
    }
}