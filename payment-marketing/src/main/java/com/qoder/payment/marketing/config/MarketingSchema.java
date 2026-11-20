package com.qoder.payment.marketing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * X-3a/X-3b 营销域建表（<b>单一 DDL 事实来源</b>，幂等、只增不改，H2/MySQL 双方言同构）。
 *
 * <p>为什么不只写 schema.sql / schema-mysql.sql：docker {@code sql.init.mode=never} 不重跑建表脚本，
 * 新库 pp_marketing 由 {@link MarketingDatabase} 在 Spring 上下文前 additive 创建，业务表必须在运行期补建；
 * local H2 同样由本类补建（schema.sql 只负责序列/别名/幂等表等引导对象）。与 settle 域 SettleSchema 同一约定。
 *
 * <p><b>不分片</b>：全部新表含状态机 UPDATE（coupon/red_packet/activity/points_account）与<b>全局幂等唯一键</b>
 * （request_id、(activity_id,payment_id)、(biz_no,type)），分片后跨片唯一键无法保证（§0.5 E-7），
 * 故 local/docker 均单库单表，取号走 {@code next_value_for('seq_marketing')}。
 *
 * <p><b>数据保护</b>：只 CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS / INSERT IGNORE 序列种子，
 * 绝不 DROP/TRUNCATE/DELETE/UPDATE 既有行，绝不 ALTER 既有表。
 *
 * <p><b>账本边界</b>：本库只有「营销权益账」（券/红包/积分数量/活动预算/分摊明细/审计），
 * <b>没有资金余额表</b>——真实资金一律经 accounting facade 复式记账落 pp_accounting，
 * 积分是虚拟权益单位（不是金额），只在抵扣换算时进入资金账本。
 */
public final class MarketingSchema {

    private static final Logger log = LoggerFactory.getLogger(MarketingSchema.class);

    public static final List<String> TABLES = List.of(
            "marketing_template", "coupon", "red_packet", "marketing_claim",
            "coupon_use", "red_packet_use",
            "points_account", "points_batch", "points_log",
            "activity", "activity_use", "marketing_alloc", "marketing_audit");

    public static void ensure(DataSource ds) {
        try (Connection con = ds.getConnection()) {
            con.setAutoCommit(true);
            String product = con.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            for (String ddl : statements(h2)) {
                try (Statement st = con.createStatement()) {
                    st.execute(ddl);
                } catch (SQLException e) {
                    // MySQL 无 CREATE INDEX IF NOT EXISTS：索引已存在（1061 Duplicate key name）即幂等跳过
                    if (ddl.startsWith("CREATE INDEX") && isDuplicateIndex(e)) {
                        continue;
                    }
                    throw e;
                }
            }
            log.info("X-3a/X-3b 营销域表校验完成 product={} tables={}（单库不分片，只增不改；资金账本在 accounting）",
                    product, TABLES);
        } catch (SQLException e) {
            throw new IllegalStateException("X-3 营销域建表失败（只增不改，失败即启动失败避免静默错账）: "
                    + e.getMessage(), e);
        }
    }

    private static List<String> statements(boolean h2) {
        String tail = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        String text = h2 ? "CLOB" : "LONGTEXT";
        List<String> out = new ArrayList<>();

        // ---------- X-3a 模板（券/红包共用，kind 区分） ----------
        out.add("CREATE TABLE IF NOT EXISTS marketing_template ("
                + "id               VARCHAR(32) PRIMARY KEY,"
                + "kind             VARCHAR(16) NOT NULL,"
                + "name             VARCHAR(128) NOT NULL,"
                + "amount           BIGINT      NOT NULL,"
                + "threshold        BIGINT      NOT NULL DEFAULT 0,"
                + "total_stock      INT         NOT NULL DEFAULT 0,"
                + "issued           INT         NOT NULL DEFAULT 0,"
                + "per_member_limit INT         NOT NULL DEFAULT 0,"
                + "valid_days       INT,"
                + "expire_at        BIGINT,"
                + "status           VARCHAR(16) NOT NULL,"
                + "rule             " + text + ","
                + "operator         VARCHAR(64),"
                + "created_at       BIGINT      NOT NULL,"
                + "updated_at       BIGINT      NOT NULL)" + tail);

        // ---------- X-3a 优惠券（发放幂等键 request_id UNIQUE） ----------
        out.add("CREATE TABLE IF NOT EXISTS coupon ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "member_id   VARCHAR(32) NOT NULL,"
                + "template_id VARCHAR(32),"
                + "amount      BIGINT      NOT NULL,"
                + "threshold   BIGINT      NOT NULL DEFAULT 0,"
                + "status      VARCHAR(16) NOT NULL,"
                + "expire_at   BIGINT      NOT NULL,"
                + "request_id  VARCHAR(64) NOT NULL,"
                + "biz_no      VARCHAR(64),"
                + "use_id      VARCHAR(32),"
                + "operator    VARCHAR(64),"
                + "created_at  BIGINT      NOT NULL,"
                + "updated_at  BIGINT      NOT NULL,"
                + "CONSTRAINT uk_coupon_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_coupon_member", "coupon", "member_id, status"));
        out.add(index(h2, "idx_coupon_expire", "coupon", "status, expire_at"));

        // ---------- X-3a 红包 ----------
        out.add("CREATE TABLE IF NOT EXISTS red_packet ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "member_id   VARCHAR(32) NOT NULL,"
                + "template_id VARCHAR(32),"
                + "amount      BIGINT      NOT NULL,"
                + "status      VARCHAR(16) NOT NULL,"
                + "expire_at   BIGINT      NOT NULL,"
                + "request_id  VARCHAR(64) NOT NULL,"
                + "biz_no      VARCHAR(64),"
                + "use_id      VARCHAR(32),"
                + "operator    VARCHAR(64),"
                + "created_at  BIGINT      NOT NULL,"
                + "updated_at  BIGINT      NOT NULL,"
                + "CONSTRAINT uk_red_packet_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_red_packet_member", "red_packet", "member_id, status"));
        out.add(index(h2, "idx_red_packet_expire", "red_packet", "status, expire_at"));

        // ---------- X-3a 领取留痕（每人限领计数 + 领取幂等） ----------
        out.add("CREATE TABLE IF NOT EXISTS marketing_claim ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "template_id VARCHAR(32) NOT NULL,"
                + "member_id   VARCHAR(32) NOT NULL,"
                + "kind        VARCHAR(16) NOT NULL,"
                + "voucher_id  VARCHAR(32) NOT NULL,"
                + "biz_no      VARCHAR(64) NOT NULL,"
                + "created_at  BIGINT      NOT NULL,"
                + "CONSTRAINT uk_marketing_claim UNIQUE (template_id, member_id, biz_no))" + tail);
        out.add(index(h2, "idx_claim_member", "marketing_claim", "template_id, member_id"));

        // ---------- X-3a 券核销单（一券只核销一次：coupon_id UNIQUE；request_id UNIQUE） ----------
        out.add("CREATE TABLE IF NOT EXISTS coupon_use ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "coupon_id   VARCHAR(32) NOT NULL,"
                + "payment_id  VARCHAR(32) NOT NULL,"
                + "order_id    VARCHAR(32),"
                + "member_id   VARCHAR(32) NOT NULL,"
                + "account_id  VARCHAR(32) NOT NULL,"
                + "amount      BIGINT      NOT NULL,"
                + "biz_no      VARCHAR(64) NOT NULL,"
                + "status      VARCHAR(16) NOT NULL,"
                + "request_id  VARCHAR(64) NOT NULL,"
                + "operator    VARCHAR(64),"
                + "created_at  BIGINT      NOT NULL,"
                + "updated_at  BIGINT      NOT NULL,"
                + "CONSTRAINT uk_coupon_use_coupon UNIQUE (coupon_id),"
                + "CONSTRAINT uk_coupon_use_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_coupon_use_payment", "coupon_use", "payment_id"));

        // ---------- X-3a 红包核销单 ----------
        out.add("CREATE TABLE IF NOT EXISTS red_packet_use ("
                + "id            VARCHAR(32) PRIMARY KEY,"
                + "red_packet_id VARCHAR(32) NOT NULL,"
                + "payment_id    VARCHAR(32) NOT NULL,"
                + "order_id      VARCHAR(32),"
                + "member_id     VARCHAR(32) NOT NULL,"
                + "account_id    VARCHAR(32) NOT NULL,"
                + "amount        BIGINT      NOT NULL,"
                + "biz_no        VARCHAR(64) NOT NULL,"
                + "status        VARCHAR(16) NOT NULL,"
                + "request_id    VARCHAR(64) NOT NULL,"
                + "operator      VARCHAR(64),"
                + "created_at    BIGINT      NOT NULL,"
                + "updated_at    BIGINT      NOT NULL,"
                + "CONSTRAINT uk_rp_use_packet UNIQUE (red_packet_id),"
                + "CONSTRAINT uk_rp_use_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_rp_use_payment", "red_packet_use", "payment_id"));

        // ---------- X-3b 积分账户（虚拟权益独立账本，单位=积分，非金额） ----------
        out.add("CREATE TABLE IF NOT EXISTS points_account ("
                + "member_id  VARCHAR(32) PRIMARY KEY,"
                + "balance    BIGINT      NOT NULL DEFAULT 0,"
                + "frozen     BIGINT      NOT NULL DEFAULT 0,"
                + "expired    BIGINT      NOT NULL DEFAULT 0,"
                + "version    BIGINT      NOT NULL DEFAULT 0,"
                + "created_at BIGINT      NOT NULL,"
                + "updated_at BIGINT      NOT NULL)" + tail);

        // ---------- X-3b 积分批次（FIFO 消耗 + 按批过期） ----------
        out.add("CREATE TABLE IF NOT EXISTS points_batch ("
                + "id         VARCHAR(32) PRIMARY KEY,"
                + "member_id  VARCHAR(32) NOT NULL,"
                + "amount     BIGINT      NOT NULL,"
                + "remaining  BIGINT      NOT NULL,"
                + "expire_at  BIGINT      NOT NULL,"
                + "status     VARCHAR(16) NOT NULL,"
                + "biz_no     VARCHAR(64) NOT NULL,"
                + "created_at BIGINT      NOT NULL,"
                + "updated_at BIGINT      NOT NULL,"
                + "CONSTRAINT uk_points_batch_biz UNIQUE (biz_no))" + tail);
        out.add(index(h2, "idx_points_batch_fifo", "points_batch", "member_id, status, expire_at"));

        // ---------- X-3b 积分流水（只追加，UNIQUE(biz_no,type) 幂等） ----------
        out.add("CREATE TABLE IF NOT EXISTS points_log ("
                + "id            VARCHAR(32) PRIMARY KEY,"
                + "member_id     VARCHAR(32) NOT NULL,"
                + "change_amount BIGINT      NOT NULL,"
                + "balance_after BIGINT      NOT NULL,"
                + "frozen_after  BIGINT      NOT NULL,"
                + "type          VARCHAR(16) NOT NULL,"
                + "biz_no        VARCHAR(64) NOT NULL,"
                + "ref_id        VARCHAR(64),"
                + "fund_biz_no   VARCHAR(64),"
                + "account_id    VARCHAR(32),"
                + "amount        BIGINT,"
                + "operator      VARCHAR(64),"
                + "created_at    BIGINT      NOT NULL,"
                + "CONSTRAINT uk_points_log UNIQUE (biz_no, type))" + tail);
        out.add(index(h2, "idx_points_log_member", "points_log", "member_id, created_at"));

        // ---------- X-3b 活动（预算原子扣减 used + n <= budget） ----------
        out.add("CREATE TABLE IF NOT EXISTS activity ("
                + "id              VARCHAR(32) PRIMARY KEY,"
                + "name            VARCHAR(128) NOT NULL,"
                + "type            VARCHAR(16) NOT NULL,"
                + "threshold       BIGINT      NOT NULL DEFAULT 0,"
                + "discount_amount BIGINT      NOT NULL DEFAULT 0,"
                + "discount_bps    INT         NOT NULL DEFAULT 0,"
                + "max_discount    BIGINT      NOT NULL DEFAULT 0,"
                + "budget          BIGINT      NOT NULL DEFAULT 0,"
                + "used            BIGINT      NOT NULL DEFAULT 0,"
                + "status          VARCHAR(16) NOT NULL,"
                + "expire_at       BIGINT,"
                + "rule            " + text + ","
                + "operator        VARCHAR(64),"
                + "created_at      BIGINT      NOT NULL,"
                + "updated_at      BIGINT      NOT NULL)" + tail);
        out.add(index(h2, "idx_activity_status", "activity", "status, expire_at"));

        // ---------- X-3b 活动核销单（一活动一支付单只核销一次） ----------
        out.add("CREATE TABLE IF NOT EXISTS activity_use ("
                + "id           VARCHAR(32) PRIMARY KEY,"
                + "activity_id  VARCHAR(32) NOT NULL,"
                + "payment_id   VARCHAR(32) NOT NULL,"
                + "order_id     VARCHAR(32),"
                + "member_id    VARCHAR(32) NOT NULL,"
                + "account_id   VARCHAR(32) NOT NULL,"
                + "amount       BIGINT      NOT NULL,"
                + "order_amount BIGINT      NOT NULL,"
                + "biz_no       VARCHAR(64) NOT NULL,"
                + "status       VARCHAR(16) NOT NULL,"
                + "request_id   VARCHAR(64) NOT NULL,"
                + "operator     VARCHAR(64),"
                + "created_at   BIGINT      NOT NULL,"
                + "updated_at   BIGINT      NOT NULL,"
                + "CONSTRAINT uk_activity_use UNIQUE (activity_id, payment_id),"
                + "CONSTRAINT uk_activity_use_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_activity_use_payment", "activity_use", "payment_id"));

        // ---------- X-3b 分摊明细（来源维度 + 目标维度，只追加，双唯一键防重） ----------
        out.add("CREATE TABLE IF NOT EXISTS marketing_alloc ("
                + "id         VARCHAR(32) PRIMARY KEY,"
                + "payment_id VARCHAR(32) NOT NULL,"
                + "dimension  VARCHAR(16) NOT NULL,"
                + "source     VARCHAR(16) NOT NULL,"
                + "target_id  VARCHAR(64) NOT NULL,"
                + "amount     BIGINT      NOT NULL,"
                + "type       VARCHAR(16) NOT NULL,"
                + "biz_no     VARCHAR(128) NOT NULL,"
                + "created_at BIGINT      NOT NULL,"
                + "CONSTRAINT uk_alloc_row UNIQUE (payment_id, dimension, source, target_id, type),"
                + "CONSTRAINT uk_alloc_biz UNIQUE (biz_no))" + tail);
        out.add(index(h2, "idx_alloc_payment", "marketing_alloc", "payment_id, dimension"));

        // ---------- 审计留痕（只追加） ----------
        out.add("CREATE TABLE IF NOT EXISTS marketing_audit ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "entity_type VARCHAR(16) NOT NULL,"
                + "entity_id   VARCHAR(64) NOT NULL,"
                + "action      VARCHAR(24) NOT NULL,"
                + "from_status VARCHAR(16),"
                + "to_status   VARCHAR(16),"
                + "amount      BIGINT,"
                + "biz_no      VARCHAR(128),"
                + "operator    VARCHAR(64),"
                + "detail      " + text + ","
                + "created_at  BIGINT      NOT NULL)" + tail);
        out.add(index(h2, "idx_audit_entity", "marketing_audit", "entity_id, created_at"));

        // ---------- 引导对象：幂等表 / 锁表 / 序列（与既有域同构） ----------
        out.add("CREATE TABLE IF NOT EXISTS idempotent_record ("
                + "biz_type   VARCHAR(32) NOT NULL,"
                + "biz_no     VARCHAR(64) NOT NULL,"
                + "result     " + text + ","
                + "created_at BIGINT      NOT NULL,"
                + "PRIMARY KEY (biz_type, biz_no))" + tail);
        out.add("CREATE TABLE IF NOT EXISTS shedlock ("
                + "name       VARCHAR(64)  NOT NULL PRIMARY KEY,"
                + "lock_until BIGINT       NOT NULL,"
                + "locked_at  BIGINT       NOT NULL,"
                + "locked_by  VARCHAR(255) NOT NULL)" + tail);
        // MySQL 取号序列表（本域运行期取号走 Java 侧 UPDATE sequences + LAST_INSERT_ID，
        // 不依赖存储函数 next_value_for —— 免 SUPER/log_bin_trust_function_creators 权限，
        // 与 schema-mysql.sql 的函数口径完全等价：同一行原子 +1，跨实例不重号）。
        if (!h2) {
            out.add("CREATE TABLE IF NOT EXISTS sequences ("
                    + "name  VARCHAR(64) PRIMARY KEY,"
                    + "value BIGINT NOT NULL)" + tail);
        }
        out.add(h2 ? "CREATE SEQUENCE IF NOT EXISTS seq_marketing START WITH 1"
                : "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_marketing', 0)");
        return out;
    }

    private static String index(boolean h2, String name, String table, String columns) {
        return h2 ? "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")"
                : "CREATE INDEX " + name + " ON " + table + " (" + columns + ")";
    }

    private static boolean isDuplicateIndex(SQLException e) {
        return e.getErrorCode() == 1061
                || (e.getMessage() != null && e.getMessage().contains("Duplicate key name"));
    }

    private MarketingSchema() {
    }
}