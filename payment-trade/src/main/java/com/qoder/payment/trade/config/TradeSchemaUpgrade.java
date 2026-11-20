package com.qoder.payment.trade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * T-1/T-2/T-3/T-4 交易库增量升级（<b>幂等、只增不改，双 profile 同构</b>）。
 *
 * <p><b>为什么在 BeanFactoryPostProcessor 阶段执行</b>：docker 下 {@code trade_order} 已分 4 片，
 * {@code ShardTableBootstrap} 在 ShardingSphere DataSource 创建时按 {@code CREATE ... LIKE} 建片并
 * <b>逐行逐列</b>比对原单表与分片表。新增列必须在它之前同时补到原单表与既有分片表，
 * 否则列集不一致会造成影子复制失败/切流被拒。故本升级早于一切 DataSource Bean 执行。
 *
 * <p>local：{@code schema.sql} 已用 {@code ADD COLUMN IF NOT EXISTS} 建好，本升级为空操作
 * （H2 内存库首连时表尚未建，探测不到即跳过，交由 schema.sql 建全）。
 * docker：{@code sql.init.mode=never}，现存 pp_trade 由本升级补列/补表，无需手工 ALTER、不改 ops-pro。
 *
 * <p><b>数据保护</b>：只 {@code ADD COLUMN} / {@code CREATE TABLE IF NOT EXISTS} / {@code INSERT IGNORE} 序列，
 * 绝不 DROP/TRUNCATE/DELETE/UPDATE 任何既有行；存量订单 product_type 由 DB 默认值回填 INSTANT。
 */
public class TradeSchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(TradeSchemaUpgrade.class);

    private final Environment env;

    public TradeSchemaUpgrade(Environment env) {
        this.env = env;
    }

    public void upgrade() {
        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return;
        }
        String user = env.getProperty("spring.datasource.username", "sa");
        String password = env.getProperty("spring.datasource.password", "");
        try (Connection con = DriverManager.getConnection(url, user, password)) {
            con.setAutoCommit(true);
            String product = con.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            if (!tableExists(con, "trade_order")) {
                // 全新库（含 local H2 内存库）：schema.sql 会建全量结构，此处不抢先建表
                log.info("交易库增量升级跳过（trade_order 尚未建表，交由 schema.sql）product={}", product);
                return;
            }
            List<String> targets = new ArrayList<>();
            targets.add("trade_order");
            if (!h2) {
                int shardCount = env.getProperty("payment.sharding.shard-count", Integer.class, 4);
                boolean sharding = Boolean.parseBoolean(env.getProperty("payment.sharding.enabled", "false"));
                if (sharding) {
                    for (int k = 0; k < shardCount; k++) {
                        String physical = "trade_order_" + k;
                        if (tableExists(con, physical)) {
                            targets.add(physical);
                        }
                    }
                }
            }
            for (String table : targets) {
                addColumn(con, h2, table, "product_type", "VARCHAR(16) NOT NULL DEFAULT 'INSTANT'");
                addColumn(con, h2, table, "payee_merchant_id", "VARCHAR(32)");
                addColumn(con, h2, table, "escrow_status", "VARCHAR(16)");
                addColumn(con, h2, table, "expire_at", "BIGINT");
                // P2-H T-5/T-6 additive 列（合并/分阶段/改价；既有列与唯一键一字未改）
                addColumn(con, h2, table, "merge_id", "VARCHAR(32)");
                addColumn(con, h2, table, "stage_count", "INT");
                addColumn(con, h2, table, "paid_stage", "INT NOT NULL DEFAULT 0");
                addColumn(con, h2, table, "paid_amount", "BIGINT NOT NULL DEFAULT 0");
                addColumn(con, h2, table, "original_amount", "BIGINT");
                addColumn(con, h2, table, "price_change_count", "INT NOT NULL DEFAULT 0");
            }
            createTables(con, h2);
            createMergeStageTables(con, h2);
            log.info("交易库增量升级完成 product={} tables={} 产品列/担保/预授权/超时留痕表就绪（只增不改）",
                    product, targets);
        } catch (SQLException e) {
            throw new IllegalStateException("交易库增量升级失败（fail-fast，避免缺列导致产品状态静默丢失）: "
                    + e.getMessage(), e);
        }
    }

    private void createTables(Connection con, boolean h2) throws SQLException {
        String suffix = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        // T-1 担保确认留痕（单库不分片：order_id UNIQUE 是全局幂等键）
        execute(con, "CREATE TABLE IF NOT EXISTS escrow_confirm_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "payment_id VARCHAR(32),"
                + "amount BIGINT NOT NULL,"
                + "fee BIGINT NOT NULL DEFAULT 0,"
                + "type VARCHAR(16) NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "hold_account_id VARCHAR(32),"
                + "payee_account_id VARCHAR(32),"
                + "operator VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_escrow_order UNIQUE (order_id))" + suffix);
        // T-2 预授权单（单库不分片：order_id UNIQUE）
        execute(con, "CREATE TABLE IF NOT EXISTS preauth_record ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "account_id VARCHAR(32) NOT NULL,"
                + "auth_amount BIGINT NOT NULL,"
                + "completed_amount BIGINT NOT NULL DEFAULT 0,"
                + "status VARCHAR(24) NOT NULL,"
                + "freeze_id VARCHAR(32),"
                + "request_id VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT,"
                + "CONSTRAINT uk_preauth_order UNIQUE (order_id))" + suffix);
        // T-2 完成扣款留痕（单库不分片：request_id / biz_no 全局幂等键）
        execute(con, "CREATE TABLE IF NOT EXISTS preauth_complete_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "request_id VARCHAR(64) NOT NULL,"
                + "released_freeze_id VARCHAR(32),"
                + "refreeze_id VARCHAR(32),"
                + "status VARCHAR(16),"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_preauth_complete_req UNIQUE (request_id),"
                + "CONSTRAINT uk_preauth_complete_biz UNIQUE (biz_no))" + suffix);
        // T-4 超时关闭留痕（单库不分片：order_id UNIQUE ⇒ 不重不漏审计）
        execute(con, "CREATE TABLE IF NOT EXISTS order_expire_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "closed_at BIGINT NOT NULL,"
                + "type VARCHAR(16) NOT NULL,"
                + "reopened_at BIGINT,"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_expire_order UNIQUE (order_id))" + suffix);
        // T-1 系统担保户登记（单库不分片：name PK 即跨实例占位闸门）
        execute(con, "CREATE TABLE IF NOT EXISTS trade_system_account ("
                + "name VARCHAR(32) PRIMARY KEY,"
                + "member_id VARCHAR(32),"
                + "account_id VARCHAR(32),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT)" + suffix);
        // I-3 ShedLock 锁表（与 common ShedLockSchemaInitializer 同一 DDL；提前建好供分片元数据加载）
        execute(con, "CREATE TABLE IF NOT EXISTS shedlock ("
                + "name VARCHAR(64) NOT NULL PRIMARY KEY,"
                + "lock_until BIGINT NOT NULL,"
                + "locked_at BIGINT NOT NULL,"
                + "locked_by VARCHAR(255) NOT NULL)" + suffix);
        if (h2) {
            execute(con, "CREATE SEQUENCE IF NOT EXISTS seq_preauth START WITH 1");
            execute(con, "CREATE SEQUENCE IF NOT EXISTS seq_preauth_complete START WITH 1");
        } else {
            execute(con, "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_preauth', 0)");
            execute(con, "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_preauth_complete', 0)");
        }
    }

    /**
     * P2-H T-5/T-6 新表（全部单库不分片：request_id/order_id 全局幂等键 + 含 UPDATE）。
     * 只 CREATE IF NOT EXISTS / INSERT IGNORE 序列，绝不 DROP/TRUNCATE/DELETE/UPDATE 既有行。
     */
    private void createMergeStageTables(Connection con, boolean h2) throws SQLException {
        String suffix = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        execute(con, "CREATE TABLE IF NOT EXISTS merge_order ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "merchant_id VARCHAR(32) NOT NULL,"
                + "total_amount BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "request_id VARCHAR(64) NOT NULL,"
                + "carrier_order_id VARCHAR(32),"
                + "payment_id VARCHAR(32),"
                + "order_count INT NOT NULL,"
                + "paid_count INT NOT NULL DEFAULT 0,"
                + "created_at BIGINT NOT NULL,"
                + "paid_at BIGINT,"
                + "CONSTRAINT uk_merge_request UNIQUE (request_id))" + suffix);
        execute(con, "CREATE TABLE IF NOT EXISTS merge_order_rel ("
                + "merge_id VARCHAR(32) NOT NULL,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "fee BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (merge_id, order_id))" + suffix);
        execute(con, "CREATE TABLE IF NOT EXISTS order_stage_plan ("
                + "order_id VARCHAR(32) NOT NULL,"
                + "stage_seq INT NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "PRIMARY KEY (order_id, stage_seq))" + suffix);
        execute(con, "CREATE TABLE IF NOT EXISTS stage_pay_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "stage_seq INT NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "request_id VARCHAR(64) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_stage_payment UNIQUE (payment_id),"
                + "CONSTRAINT uk_stage_request UNIQUE (request_id))" + suffix);
        relaxStageOrderSeqIndex(con, h2);
        execute(con, "CREATE TABLE IF NOT EXISTS order_price_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "old_amount BIGINT NOT NULL,"
                + "new_amount BIGINT NOT NULL,"
                + "delta BIGINT NOT NULL,"
                + "direction VARCHAR(16) NOT NULL,"
                + "reason VARCHAR(256),"
                + "operator VARCHAR(64),"
                + "request_id VARCHAR(64) NOT NULL,"
                + "refund_id VARCHAR(32),"
                + "supplement_order_id VARCHAR(32),"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_price_request UNIQUE (request_id))" + suffix);
        if (h2) {
            execute(con, "CREATE SEQUENCE IF NOT EXISTS seq_merge START WITH 1");
            execute(con, "CREATE SEQUENCE IF NOT EXISTS seq_stage_log START WITH 1");
            execute(con, "CREATE SEQUENCE IF NOT EXISTS seq_price_log START WITH 1");
        } else {
            execute(con, "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_merge', 0)");
            execute(con, "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_stage_log', 0)");
            execute(con, "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_price_log', 0)");
        }
    }

    /**
     * T-6 撤销后重付：把期级唯一键 {@code uk_stage_order_seq} 降为普通索引 {@code idx_stage_order_seq}。
     *
     * <p>原唯一键挡住「同一期当日撤销后重付」的合法 INSERT（Duplicate entry order-seq）；
     * 而改成复用旧留痕行又会破坏 {@code releaseStageAfterCancel} 按旧 paymentId 的幂等重入
     * （原设计明确要求「后续期重付不影响本留痕终态」，即每次 attempt 独立留痕行）。
     *
     * <p>双付守卫不因此削弱：订单级 {@code reserveStage} CAS（须顺序且 paidStage=seq-1）
     * + {@code uk_stage_payment}（一支付单一留痕）+ {@code uk_stage_request}（requestId 幂等）。
     *
     * <p><b>只动索引，绝不 DROP/TRUNCATE/DELETE/UPDATE 任何行</b>；幂等，双 profile 同构。
     */
    private void relaxStageOrderSeqIndex(Connection con, boolean h2) throws SQLException {
        if (!tableExists(con, "stage_pay_log")) {
            return;
        }
        if (h2) {
            execute(con, "ALTER TABLE stage_pay_log DROP CONSTRAINT IF EXISTS uk_stage_order_seq");
            execute(con, "CREATE INDEX IF NOT EXISTS idx_stage_order_seq ON stage_pay_log (order_id, stage_seq)");
            return;
        }
        boolean unique = indexExists(con, "stage_pay_log", "uk_stage_order_seq");
        boolean plain = indexExists(con, "stage_pay_log", "idx_stage_order_seq");
        if (unique) {
            execute(con, "ALTER TABLE stage_pay_log DROP INDEX uk_stage_order_seq"
                    + (plain ? "" : ", ADD INDEX idx_stage_order_seq (order_id, stage_seq)"));
            log.warn("阶段留痕期级唯一键降为普通索引（撤销后可重付；双付守卫由订单CAS+payment/request唯一键承担）");
        } else if (!plain) {
            execute(con, "ALTER TABLE stage_pay_log ADD INDEX idx_stage_order_seq (order_id, stage_seq)");
        }
    }

    private static boolean indexExists(Connection con, String table, String index) throws SQLException {
        try (java.sql.PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND index_name=? LIMIT 1")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void addColumn(Connection con, boolean h2, String table, String column, String type) throws SQLException {
        if (h2) {
            execute(con, "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
            return;
        }
        if (columnExists(con, table, column)) {
            return;
        }
        execute(con, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("交易库补列 {}.{} {}", table, column, type);
    }

    private static boolean tableExists(Connection con, String table) {
        return probe(con, "SELECT 1 FROM " + table + " WHERE 1=0");
    }

    private static boolean columnExists(Connection con, String table, String column) {
        return probe(con, "SELECT " + column + " FROM " + table + " WHERE 1=0");
    }

    private static boolean probe(Connection con, String sql) {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void execute(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}