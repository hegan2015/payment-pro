package com.qoder.payment.cashier.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * C-2/C-3 建表兜底入口（双 profile）：Bean 初始化阶段执行，早于 Dubbo 服务导出，
 * 杜绝「服务已接流量但表尚未建好」窗口。local schema.sql 已建 → 幂等空操作；
 * docker pp_cashier（createDatabaseIfNotExist 自动建库）→ 启动期补建表。只增不改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashierSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("C-2/C-3 建表失败：JdbcTemplate 无 DataSource");
        }
        CashierSchema.ensure(ds);
    }
}