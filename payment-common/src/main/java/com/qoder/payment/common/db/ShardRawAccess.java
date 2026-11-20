package com.qoder.payment.common.db;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * I-5b 原单库直连通道（DDL / information_schema 专用）。
 *
 * <p>背景：各域 {@code *SchemaUpgrade} 用自动装配的 {@link JdbcTemplate}（挂在 {@code @Primary}
 * 路由 DataSource 上）执行 {@code ALTER TABLE} 与 {@code information_schema.columns} 探测。
 * 分片态下这类语句会被 ShardingSphere 接管：
 * <ul>
 *   <li>{@code ALTER TABLE payment} 只会广播到 payment_0..3，<b>原单表 payment 拿不到新列</b>
 *       → 影子副本缺列、回滚后 SQL 报错；</li>
 *   <li>{@code information_schema.columns} 不是 ShardingSphere 已知逻辑表，可能直接解析失败。</li>
 * </ul>
 *
 * <p>故结构升级一律走<b>原单库直连</b>：本工具把路由 DataSource 解包成
 * {@link ShardingToggleDataSource#singleDataSource()}，未装配分片时原样返回（local 零影响）。
 * 只读元数据 / 只增列，绝不 UPDATE/DELETE 既有业务行。
 */
public final class ShardRawAccess {

    /** 解包：分片路由 DataSource → 原单库 DataSource；其它（H2 单库）原样返回。 */
    public static DataSource unwrap(DataSource ds) {
        if (ds instanceof ShardingToggleDataSource toggle) {
            return toggle.singleDataSource();
        }
        return ds;
    }

    /** 结构升级专用 JdbcTemplate：分片态直连原单库，未分片态即原 JdbcTemplate（同一实例）。 */
    public static JdbcTemplate raw(JdbcTemplate jdbc) {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            return jdbc;
        }
        DataSource raw = unwrap(ds);
        return raw == ds ? jdbc : new JdbcTemplate(raw);
    }

    private ShardRawAccess() {
    }
}