package com.qoder.payment.account.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 账户库增量升级（A-4 多币种，幂等、<b>只增不改</b>，双 profile 同构）。
 *
 * <p>docker profile {@code sql.init.mode=never}，现存 pp_account 由本升级在 Bean 阶段补齐：
 * <ol>
 *   <li>{@code account} additive 加列 {@code currency VARCHAR(8) DEFAULT 'CNY'}；</li>
 *   <li>新列回填 {@code UPDATE account SET currency='CNY' WHERE currency IS NULL}
 *       —— <b>只触碰新增列</b>，既有业务列（余额/状态/类型/等级）一字不改，与 A-1 account_type 回填同一先例；</li>
 *   <li>唯一键迁移 {@code uk_account_member_type(member_id,account_type)} →
 *       {@code uk_account_member_type_ccy(member_id,account_type,currency)}：
 *       存量行全部 CNY ⇒ 对既有 CNY 户的唯一性与 A-1 幂等语义<b>逐字节等价</b>，
 *       只是额外允许同会员同类型的外币户（多币种会员多账户）。</li>
 * </ol>
 *
 * <p><b>数据保护红线</b>：绝不 DROP TABLE / TRUNCATE / DELETE 任何行；不改动既有业务列值。
 * <p>local：schema.sql 已同构声明，本升级为空操作（H2 用 IF NOT EXISTS 守卫）。
 * <p>account 域<b>不分片</b>（分片只在 payment/accounting/trade 的资金表），无物理分片表需要补列。
 */
@Slf4j
@Component("accountSchemaUpgrade")
public class AccountSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    public AccountSchemaUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        String product = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase(Locale.ROOT).contains("H2");
        if (!tableExists("account")) {
            log.warn("A-4 账户库升级跳过：account 表尚未建立 product={}", product);
            return;
        }
        addColumn(h2, "currency", "VARCHAR(8) DEFAULT 'CNY'");
        // 新列回填（仅新增列，存量业务列不动）：保证唯一键与缺省币种查询对既有行生效
        int backfilled = jdbc.update("UPDATE account SET currency='CNY' WHERE currency IS NULL");
        migrateUniqueKey(h2);
        // ---------- A-5 additive（生命周期）：只增列 + 只建新表，绝不 DROP/DELETE 既有行 ----------
        addColumn(h2, "lifecycle_status", "VARCHAR(16) DEFAULT 'ACTIVE'");
        addColumn(h2, "closed_at", "BIGINT");
        int lifecycleBackfilled = jdbc.update("UPDATE account SET lifecycle_status='ACTIVE' WHERE lifecycle_status IS NULL");
        ensureLifecycleLog(h2);
        log.info("A-4/A-5 账户库增量校验完成 product={} currency=ready backfilledRows={} lifecycle=ready "
                        + "lifecycleBackfilledRows={} uk=member_id+account_type+currency",
                product, backfilled, lifecycleBackfilled);
    }

    /**
     * A-5 生命周期留痕表（append-only）+ 取号序列。幂等建表，双方言同构。
     * <b>销户是逻辑状态</b>：account 行永久保留，本表只追加 OPEN/CLOSE_REQUEST/CLOSE/CLOSE_ABORT/UPGRADE/DOWNGRADE。
     */
    private void ensureLifecycleLog(boolean h2) {
        String tail = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_lifecycle_log ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "account_id  VARCHAR(32) NOT NULL,"
                + "action      VARCHAR(24) NOT NULL,"
                + "from_status VARCHAR(16),"
                + "to_status   VARCHAR(16),"
                + "from_level  VARCHAR(8),"
                + "to_level    VARCHAR(8),"
                + "operator    VARCHAR(64),"
                + "reason      VARCHAR(256),"
                + "biz_no      VARCHAR(64),"
                + "created_at  BIGINT      NOT NULL)" + tail);
        if (h2) {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_account_lifecycle START WITH 1");
        } else {
            jdbc.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_account_lifecycle', 0)");
        }
        try {
            jdbc.execute(h2 ? "CREATE INDEX IF NOT EXISTS idx_lifecycle_account ON account_lifecycle_log (account_id)"
                    : "CREATE INDEX idx_lifecycle_account ON account_lifecycle_log (account_id)");
        } catch (Exception e) {
            // MySQL 无 CREATE INDEX IF NOT EXISTS：索引已存在即幂等跳过
            log.debug("A-5 留痕索引已存在（幂等跳过）: {}", e.getMessage());
        }
    }

    /**
     * 唯一键 additive 迁移：旧键存在则重建为含 currency 的三列键。
     * 存量行 currency 已回填 CNY ⇒ 唯一性约束对既有数据完全等价（不会因迁移产生冲突）。
     * <b>顺序关键</b>：先建三列键、再删旧两列键 —— 反序在已有外币户时会因旧键重建失败而阻断启动。
     */
    private void migrateUniqueKey(boolean h2) {
        if (indexExists("uk_account_member_type_ccy")) {
            return;
        }
        if (h2) {
            jdbc.execute("ALTER TABLE account ADD CONSTRAINT IF NOT EXISTS uk_account_member_type_ccy "
                    + "UNIQUE (member_id, account_type, currency)");
            jdbc.execute("ALTER TABLE account DROP CONSTRAINT IF EXISTS uk_account_member_type");
            return;
        }
        jdbc.execute("ALTER TABLE account ADD UNIQUE KEY uk_account_member_type_ccy (member_id, account_type, currency)");
        if (indexExists("uk_account_member_type")) {
            jdbc.execute("ALTER TABLE account DROP INDEX uk_account_member_type");
        }
    }

    private void addColumn(boolean h2, String column, String type) {
        if (h2) {
            jdbc.execute("ALTER TABLE account ADD COLUMN IF NOT EXISTS " + column + " " + type);
            return;
        }
        if (columnExists(column)) {
            return;
        }
        jdbc.execute("ALTER TABLE account ADD COLUMN " + column + " " + type);
        log.info("A-4 账户库 additive 加列 table=account column={} type={}", column, type);
    }

    private boolean tableExists(String table) {
        try {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
            return found != null && found > 0;
        } catch (Exception e) {
            try {
                jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1=0");
                return true;
            } catch (Exception absent) {
                return false;
            }
        }
    }

    private boolean columnExists(String column) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='account' AND column_name=?", Integer.class, column);
        return found != null && found > 0;
    }

    private boolean indexExists(String index) {
        try {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='account' AND index_name=?", Integer.class, index);
            return found != null && found > 0;
        } catch (Exception e) {
            // H2：information_schema 口径差异 → 回落约束名探测
            try {
                Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_name=?", Integer.class, index.toUpperCase(Locale.ROOT));
                return found != null && found > 0;
            } catch (Exception absent) {
                return false;
            }
        }
    }
}