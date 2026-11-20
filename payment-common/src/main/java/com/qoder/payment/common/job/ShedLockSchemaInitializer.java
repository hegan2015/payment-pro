package com.qoder.payment.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * I-3 shedlock 锁表增量建表（幂等、只增不改，双 profile 同构）。
 *
 * <p>local：各域 {@code schema.sql} 已建好，此处为空操作；
 * docker：{@code sql.init.mode=never} 不重跑建表脚本，现存 pp_payment / pp_notify / pp_recon
 * 由本初始化在 Bean 阶段补建（早于锁提供者与调度启动），无需手工 ALTER、不改 ops-pro。
 *
 * <p>锁表与业务表同库（不分片）：每个域用自己的 DataSource 锁自己的作业，
 * 作业名全局唯一，多实例共享同一 MySQL 库即天然互斥；local H2 单进程文件库同样成立。
 * 绝不 UPDATE/DELETE 既有数据。
 */
public class ShedLockSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ShedLockSchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final String table;

    public ShedLockSchemaInitializer(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public void afterPropertiesSet() {
        String product = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase().contains("H2");
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "name       VARCHAR(64)  NOT NULL PRIMARY KEY,"
                + "lock_until BIGINT       NOT NULL,"
                + "locked_at  BIGINT       NOT NULL,"
                + "locked_by  VARCHAR(255) NOT NULL"
                + (h2 ? ")" : ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute(ddl);
        log.info("I-3 shedlock 锁表校验完成 product={} table={}（BIGINT 毫秒）", product, table);
    }
}