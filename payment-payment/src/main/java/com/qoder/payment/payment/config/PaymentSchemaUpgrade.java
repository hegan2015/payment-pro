package com.qoder.payment.payment.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * S-5/P-1 支付库增量升级（幂等、只增不改，双 profile 同构）。
 *
 * <p>local：schema.sql 已用 {@code ADD COLUMN IF NOT EXISTS} 建好，本升级为空操作；
 * docker：{@code sql.init.mode=never} 不重跑建表脚本，现存 pp_payment 库由本升级补齐
 * payment.reconcile_count / last_reconcile_at 补单留痕列，无需手工 ALTER、不改 ops-pro。
 *
 * <p>在 Bean 初始化阶段执行（早于 Dubbo 服务导出与调度启动）。绝不 UPDATE/DELETE 既有数据；
 * dtx_transaction.status 已是 VARCHAR，tcc-core WAITING 增量无需改表。
 * 失败即启动失败（避免缺列导致补单留痕静默出错）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${payment.sharding.shard-count:4}")
    private int shardCount;

    @Override
    public void afterPropertiesSet() {
        // I-5b：结构升级一律直连原单库（分片态下 ALTER 只会广播到分片表、原单表会缺列；
        // information_schema 也不是 ShardingSphere 已知逻辑表）。未装配分片时即原 JdbcTemplate。
        JdbcTemplate jdbc = com.qoder.payment.common.db.ShardRawAccess.raw(this.jdbc);
        String product = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase().contains("H2");
        addColumn(jdbc, h2, "payment", "reconcile_count", "INT NOT NULL DEFAULT 0");
        addColumn(jdbc, h2, "payment", "last_reconcile_at", "BIGINT");
        // I-4 事务消息 + 多 topic：outbox 增列（additive，幂等键不变；只写元数据，绝不动既有行状态）
        addColumn(jdbc, h2, "outbox", "topic", "VARCHAR(32)");
        addColumn(jdbc, h2, "outbox", "msg_id", "VARCHAR(64)");
        addColumn(jdbc, h2, "outbox", "half_state", "VARCHAR(16)");
        createReviewTables(jdbc, h2);
        // P1-I 支付编排工作流（P-2/P-3/P-4/P-5 + CHALLENGE 续付）：只增不改，双 profile 同构
        addColumn(jdbc, h2, "payment", "pay_mode", "VARCHAR(16) NOT NULL DEFAULT 'SINGLE'");
        addColumn(jdbc, h2, "payment", "channel_code", "VARCHAR(32)");
        addColumn(jdbc, h2, "payment", "close_type", "VARCHAR(16)");
        addColumn(jdbc, h2, "payment", "closed_at", "BIGINT");
        addColumn(jdbc, h2, "refund_record", "channel_order_no", "VARCHAR(64)");
        addColumn(jdbc, h2, "refund_record", "reconcile_count", "INT NOT NULL DEFAULT 0");
        addColumn(jdbc, h2, "refund_record", "last_reconcile_at", "BIGINT");
        addColumn(jdbc, h2, "refund_record", "fail_reason", "VARCHAR(256)");
        addColumn(jdbc, h2, "refund_record", "leg_alloc_json", h2 ? "CLOB" : "LONGTEXT");
        createOrchestrationTables(jdbc, h2);
        // P2-E P-7 营销抵扣编排（additive：只增列/增表，绝不改写既有行；amount/fee 口径不变）
        addColumn(jdbc, h2, "payment", "marketing_amount", "BIGINT NOT NULL DEFAULT 0");
        addColumn(jdbc, h2, "payment", "marketing_status", "VARCHAR(16) NOT NULL DEFAULT 'NONE'");
        createMarketingLegTable(jdbc, h2);
        // P2-H T-5 合并支付/分阶段（additive：只增列，既有列/唯一键/fee 口径一字未改）
        addColumn(jdbc, h2, "payment", "stage_seq", "INT");
        addColumn(jdbc, h2, "payment", "merge_id", "VARCHAR(32)");
        syncShardColumns(jdbc, h2);
        log.info("支付库增量校验完成 product={} reconcileTrace=ready amountReview=ready outboxMq=ready orchestration=ready marketingOrch=ready", product);
    }

    /**
     * P2-E P-7 营销抵扣明细表（单库不分片，与 payment_leg 同一约定）：
     * UNIQUE(payment_id,leg_seq) + UNIQUE(payment_id,source,ref_id) 双重防重，只追加 + 状态 CAS。
     * local schema.sql 已建（此处幂等空操作），docker 现存 pp_payment 库启动时补建。绝不 UPDATE/DELETE 既有行。
     */
    private void createMarketingLegTable(JdbcTemplate jdbc, boolean h2) {
        if (h2) {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_marketing_leg START WITH 1");
        } else {
            jdbc.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_marketing_leg', 0)");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_marketing_leg ("
                + "id         VARCHAR(32) PRIMARY KEY,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "leg_seq    INT NOT NULL,"
                + "source     VARCHAR(16) NOT NULL,"
                + "ref_id     VARCHAR(64) NOT NULL,"
                + "use_id     VARCHAR(64),"
                + "biz_no     VARCHAR(128),"
                + "biz_key    VARCHAR(64),"
                + "amount     BIGINT NOT NULL DEFAULT 0,"
                + "points     BIGINT,"
                + "status     VARCHAR(16) NOT NULL,"
                + "fail_reason VARCHAR(256),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_mktleg_seq UNIQUE (payment_id, leg_seq),"
                + "CONSTRAINT uk_mktleg_source UNIQUE (payment_id, source, ref_id))");
        addIndex(jdbc, h2, "payment_marketing_leg", "idx_mktleg_payment", "(payment_id)");
    }

    /**
     * I-5b 分片表结构同构（additive）：payment_0..N / refund_record_0..N 若已存在
     * （早于本次增列建立），同步补齐新列——只 ADD COLUMN，绝不改写既有行。
     * 不同步会导致双写镜像/切流后 INSERT 缺列失败（资金域观察态双写红线）。
     */
    private void syncShardColumns(JdbcTemplate jdbc, boolean h2) {
        if (h2) {
            return;
        }
        for (int k = 0; k < shardCount; k++) {
            String paymentTable = "payment_" + k;
            if (tableExists(jdbc, paymentTable)) {
                addColumn(jdbc, false, paymentTable, "pay_mode", "VARCHAR(16) NOT NULL DEFAULT 'SINGLE'");
                addColumn(jdbc, false, paymentTable, "channel_code", "VARCHAR(32)");
                addColumn(jdbc, false, paymentTable, "close_type", "VARCHAR(16)");
                addColumn(jdbc, false, paymentTable, "closed_at", "BIGINT");
                // P2-E P-7：分片表必须同步补列，否则双写镜像/切流后 INSERT 缺列宕机（资金域观察态双写红线）
                addColumn(jdbc, false, paymentTable, "marketing_amount", "BIGINT NOT NULL DEFAULT 0");
                addColumn(jdbc, false, paymentTable, "marketing_status", "VARCHAR(16) NOT NULL DEFAULT 'NONE'");
                // P2-H T-5：合并/分阶段 additive 列同步（同上红线）
                addColumn(jdbc, false, paymentTable, "stage_seq", "INT");
                addColumn(jdbc, false, paymentTable, "merge_id", "VARCHAR(32)");
            }
            String refundTable = "refund_record_" + k;
            if (tableExists(jdbc, refundTable)) {
                addColumn(jdbc, false, refundTable, "channel_order_no", "VARCHAR(64)");
                addColumn(jdbc, false, refundTable, "reconcile_count", "INT NOT NULL DEFAULT 0");
                addColumn(jdbc, false, refundTable, "last_reconcile_at", "BIGINT");
                addColumn(jdbc, false, refundTable, "fail_reason", "VARCHAR(256)");
                addColumn(jdbc, false, refundTable, "leg_alloc_json", "LONGTEXT");
            }
        }
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                Integer.class, table);
        return count != null && count > 0;
    }

    /**
     * P1-I 编排新表（payment_leg/share_order/share_detail/payment_close_log/payment_challenge）：
     * 全部单库不分片；local schema.sql 已建（此处幂等空操作），docker 现存库启动时补建。
     * 绝不 UPDATE/DELETE 既有行。
     */
    private void createOrchestrationTables(JdbcTemplate jdbc, boolean h2) {
        if (h2) {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_leg START WITH 1");
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_share START WITH 1");
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_close_log START WITH 1");
        } else {
            jdbc.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_leg', 0), ('seq_share', 0), ('seq_close_log', 0)");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_leg ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "leg_seq INT NOT NULL,"
                + "method VARCHAR(16) NOT NULL,"
                + "payer_account_id VARCHAR(32),"
                + "card_no VARCHAR(32),"
                + "amount BIGINT NOT NULL,"
                + "fee BIGINT NOT NULL DEFAULT 0,"
                + "status VARCHAR(16) NOT NULL,"
                + "channel_biz_no VARCHAR(64),"
                + "channel_code VARCHAR(32),"
                + "refunded_amount BIGINT NOT NULL DEFAULT 0,"
                + "refunded_fee BIGINT NOT NULL DEFAULT 0,"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_leg_payment_seq UNIQUE (payment_id, leg_seq))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS share_order ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "merchant_id VARCHAR(32) NOT NULL,"
                + "merchant_account_id VARCHAR(32) NOT NULL,"
                + "total_amount BIGINT NOT NULL,"
                + "share_type VARCHAR(16) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "request_id VARCHAR(64) NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "execute_at BIGINT NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "shared_at BIGINT,"
                + "backed_at BIGINT,"
                + "back_request_id VARCHAR(64),"
                + "fail_reason VARCHAR(256),"
                + "CONSTRAINT uk_share_request UNIQUE (request_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS share_detail ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "share_id VARCHAR(32) NOT NULL,"
                + "receiver_account_id VARCHAR(32) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_share_receiver UNIQUE (share_id, receiver_account_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_close_log ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "type VARCHAR(16) NOT NULL,"
                + "request_id VARCHAR(64),"
                + "biz_no VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "CONSTRAINT uk_close_payment_type UNIQUE (payment_id, type))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_challenge ("
                + "request_id VARCHAR(64) PRIMARY KEY,"
                + "order_id VARCHAR(32) NOT NULL,"
                + "payer_key VARCHAR(64) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "method VARCHAR(16) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "continue_request_id VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "expire_at BIGINT NOT NULL,"
                + "consumed_at BIGINT)");
        addIndex(jdbc, h2, "payment_leg", "idx_leg_payment", "(payment_id)");
        addIndex(jdbc, h2, "share_order", "idx_share_payment", "(payment_id)");
        addIndex(jdbc, h2, "share_order", "idx_share_due", "(status, execute_at)");
        addIndex(jdbc, h2, "payment_challenge", "idx_challenge_order", "(order_id, payer_key, status)");
        addIndex(jdbc, h2, "payment_challenge", "idx_challenge_continue", "(continue_request_id)");
    }

    private void addIndex(JdbcTemplate jdbc, boolean h2, String table, String index, String columns) {
        if (h2) {
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + " " + columns);
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, index);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("CREATE INDEX " + index + " ON " + table + " " + columns);
    }

    /**
     * S-7 大额双人复核表 + 复核幂等表（只增不改，双 profile 同构）。
     * local：schema.sql 已建，此处幂等空操作；docker：现存 pp_payment 库启动时补建。
     */
    private void createReviewTables(JdbcTemplate jdbc, boolean h2) {
        if (h2) {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS seq_review START WITH 1");
        } else {
            jdbc.update("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_review', 0)");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS amount_review ("
                + "id VARCHAR(32) PRIMARY KEY,"
                + "biz_type VARCHAR(16) NOT NULL,"
                + "biz_no VARCHAR(64) NOT NULL,"
                + "amount BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "submitter VARCHAR(64) NOT NULL,"
                + "approver VARCHAR(64),"
                + "reject_reason VARCHAR(256),"
                + "created_at BIGINT NOT NULL,"
                + "reviewed_at BIGINT,"
                + "CONSTRAINT uk_review_biz UNIQUE (biz_type, biz_no))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS idempotent_record ("
                + "biz_type VARCHAR(32) NOT NULL,"
                + "biz_no VARCHAR(128) NOT NULL,"
                + (h2 ? "result CLOB," : "result LONGTEXT,")
                + "created_at BIGINT NOT NULL,"
                + "PRIMARY KEY (biz_type, biz_no))");
    }

    private void addColumn(JdbcTemplate jdbc, boolean h2, String table, String column, String type) {
        if (h2) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("支付库补列 {}.{} {}", table, column, type);
    }
}