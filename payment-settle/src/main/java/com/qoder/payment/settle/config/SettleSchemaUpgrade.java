package com.qoder.payment.settle.config;

import com.qoder.payment.common.db.ShardRawAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * ST-x 建表兜底入口（local H2 / docker 未开启分片）：Bean 初始化阶段执行，早于 Dubbo 服务导出，
 * 杜绝「服务已接流量但表尚未建好」窗口。分片态下 {@link SettleShardPreBootstrap} 已建好，此处幂等空操作。
 *
 * <p>DDL 一律走原单库直连（{@link ShardRawAccess}），不经 ShardingSphere。只增不改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettleSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("ST-x 建表失败：JdbcTemplate 无 DataSource");
        }
        SettleSchema.ensure(ShardRawAccess.unwrap(ds));
    }
}