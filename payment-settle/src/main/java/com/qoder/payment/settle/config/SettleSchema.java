package com.qoder.payment.settle.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * ST-1~ST-5 清结算工作流建表（<b>单一 DDL 事实来源</b>，幂等、只增不改，双 profile 同构）。
 *
 * <p>为什么不只写 schema.sql / schema-mysql.sql：docker {@code sql.init.mode=never} 不会重跑建表脚本，
 * 现存 pp_settle 库必须在运行期补建；且分片态下新表必须在 ShardingSphere DataSource 创建<b>之前</b>
 * 就存在（SINGLE 规则元数据加载），故 DDL 统一收敛到本类，由两个入口调用：
 * <ul>
 *   <li>{@link SettleShardPreBootstrap}（docker 分片态，原单库直连，早于分片 DataSource）；</li>
 *   <li>{@link SettleSchemaUpgrade}（local H2 / docker 未分片态，Bean 初始化阶段）。</li>
 * </ul>
 *
 * <p><b>分片归属</b>：本期新表全部<b>单库不分片</b>（SINGLE 规则钉在主库）——
 * settle_batch / withdraw_order / merchant_bill 都是<b>含可变状态的协调器表</b>（状态机 UPDATE），
 * 而 I-5a 影子写 {@code ShardMirror} 只 INSERT 不 UPDATE，若分片则回滚单库后状态列不一致，
 * 违反「回滚后逐条一致」红线；fee_rule / settle_cycle 是配置表，settle_share_detail 的唯一键
 * (share_id,receiver) 非 merchant 维度，无法按 merchant 分片保证全局唯一。
 * 跨片汇总由<b>已分片的 settle_record</b> 顶层 SUM 归并提供（ST-5 账单口径）。
 *
 * <p><b>数据保护</b>：只 CREATE TABLE IF NOT EXISTS，绝不 DROP/TRUNCATE/DELETE/UPDATE 既有行；
 * <b>不 ALTER settle_record</b>（保持其不可变 + 影子副本逐列一致）。
 */
public final class SettleSchema {

    private static final Logger log = LoggerFactory.getLogger(SettleSchema.class);

    /** 新增单库表（供 application-docker.yml 的 payment.sharding.single-tables 对齐）。 */
    public static final List<String> NEW_TABLES = List.of(
            "settle_cycle", "settle_batch", "settle_batch_item",
            "fee_rule", "fee_rule_audit", "settle_share_detail",
            "withdraw_order", "merchant_bill",
            // ST-6（P2 G 泳道）：保证金/授信/垫资，同样全部单库不分片
            "deposit_record", "credit_account", "advance_record");

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
            log.info("ST-1~ST-5 清结算工作流表校验完成 product={} tables={}（全部单库不分片，只增不改）",
                    product, NEW_TABLES);
        } catch (SQLException e) {
            throw new IllegalStateException("ST-x 清结算建表失败（只增不改，失败即启动失败避免静默错账）: "
                    + e.getMessage(), e);
        }
    }

    private static List<String> statements(boolean h2) {
        String tail = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        String text = h2 ? "CLOB" : "LONGTEXT";
        List<String> out = new ArrayList<>();

        // ST-1 商户结算周期（T+N）
        out.add("CREATE TABLE IF NOT EXISTS settle_cycle ("
                + "merchant_id VARCHAR(32) PRIMARY KEY,"
                + "cycle_days  INT         NOT NULL,"
                + "fee_bps     INT,"
                + "status      VARCHAR(16) NOT NULL,"
                + "created_at  BIGINT      NOT NULL,"
                + "updated_at  BIGINT      NOT NULL)" + tail);

        // ST-1 结算批次（状态机 PENDING/SETTLING/SETTLED/FAILED）
        out.add("CREATE TABLE IF NOT EXISTS settle_batch ("
                + "id           VARCHAR(32) PRIMARY KEY,"
                + "merchant_id  VARCHAR(32) NOT NULL,"
                + "biz_date     BIGINT      NOT NULL,"
                + "cycle_days   INT         NOT NULL,"
                + "gross        BIGINT      NOT NULL,"
                + "fee          BIGINT      NOT NULL,"
                + "net          BIGINT      NOT NULL,"
                + "refund       BIGINT      NOT NULL,"
                + "record_count INT         NOT NULL,"
                + "status       VARCHAR(16) NOT NULL,"
                + "fail_reason  VARCHAR(255),"
                + "shard_index  INT,"
                + "shard_total  INT,"
                + "created_at   BIGINT      NOT NULL,"
                + "settled_at   BIGINT,"
                + "CONSTRAINT uk_settle_batch UNIQUE (merchant_id, biz_date))" + tail);

        // ST-1 批次明细（批次 ↔ 结算单，只 INSERT，唯一键去重 ⇒ 跑批不重不漏）
        out.add("CREATE TABLE IF NOT EXISTS settle_batch_item ("
                + "id          VARCHAR(32) PRIMARY KEY,"
                + "batch_id    VARCHAR(32) NOT NULL,"
                + "record_id   VARCHAR(32) NOT NULL,"
                + "merchant_id VARCHAR(32) NOT NULL,"
                + "type        VARCHAR(16) NOT NULL,"
                + "gross       BIGINT      NOT NULL,"
                + "fee         BIGINT      NOT NULL,"
                + "net         BIGINT      NOT NULL,"
                + "created_at  BIGINT      NOT NULL,"
                + "CONSTRAINT uk_settle_batch_item UNIQUE (batch_id, record_id))" + tail);
        out.add(index(h2, "idx_batch_item_merchant", "settle_batch_item", "merchant_id"));

        // ST-2 分层费率规则（merchant/product_type/method 三层，'*' 通配）
        out.add("CREATE TABLE IF NOT EXISTS fee_rule ("
                + "id           VARCHAR(32) PRIMARY KEY,"
                + "merchant_id  VARCHAR(32) NOT NULL,"
                + "product_type VARCHAR(32) NOT NULL,"
                + "method       VARCHAR(16) NOT NULL,"
                + "fee_type     VARCHAR(16) NOT NULL,"
                + "fee_bps      INT,"
                + "tiers        " + text + ","
                + "share_ratio  INT,"
                + "status       VARCHAR(16) NOT NULL,"
                + "version      INT         NOT NULL,"
                + "created_at   BIGINT      NOT NULL,"
                + "updated_at   BIGINT      NOT NULL,"
                + "CONSTRAINT uk_fee_rule UNIQUE (merchant_id, product_type, method))" + tail);

        // ST-2 费率变更审计
        out.add("CREATE TABLE IF NOT EXISTS fee_rule_audit ("
                + "id           VARCHAR(32) PRIMARY KEY,"
                + "rule_id      VARCHAR(32) NOT NULL,"
                + "merchant_id  VARCHAR(32) NOT NULL,"
                + "product_type VARCHAR(32) NOT NULL,"
                + "method       VARCHAR(16) NOT NULL,"
                + "old_value    " + text + ","
                + "new_value    " + text + ","
                + "operator     VARCHAR(64),"
                + "created_at   BIGINT      NOT NULL)" + tail);

        // ST-3 分账结算明细（唯一键非 merchant 维度 ⇒ 单库不分片）
        out.add("CREATE TABLE IF NOT EXISTS settle_share_detail ("
                + "id                  VARCHAR(32) PRIMARY KEY,"
                + "share_id            VARCHAR(64) NOT NULL,"
                + "payment_id          VARCHAR(32) NOT NULL,"
                + "merchant_id         VARCHAR(32) NOT NULL,"
                + "merchant_account_id VARCHAR(32) NOT NULL,"
                + "receiver_account_id VARCHAR(32) NOT NULL,"
                + "amount              BIGINT      NOT NULL,"
                + "fee                 BIGINT      NOT NULL,"
                + "net                 BIGINT      NOT NULL,"
                + "type                VARCHAR(16) NOT NULL,"
                + "status              VARCHAR(16) NOT NULL,"
                + "biz_no              VARCHAR(64) NOT NULL,"
                + "created_at          BIGINT      NOT NULL,"
                + "CONSTRAINT uk_settle_share UNIQUE (share_id, receiver_account_id, type))" + tail);
        out.add(index(h2, "idx_share_merchant", "settle_share_detail", "merchant_id"));

        // ST-4 提现单（request_id 全局幂等键 ⇒ 必须单库不分片）
        out.add("CREATE TABLE IF NOT EXISTS withdraw_order ("
                + "id               VARCHAR(32) PRIMARY KEY,"
                + "account_id       VARCHAR(32) NOT NULL,"
                + "merchant_id      VARCHAR(32) NOT NULL,"
                + "amount           BIGINT      NOT NULL,"
                + "fee              BIGINT      NOT NULL,"
                + "bank_card_no     VARCHAR(64),"
                + "channel_code     VARCHAR(32),"
                + "channel_order_no VARCHAR(64),"
                + "status           VARCHAR(16) NOT NULL,"
                + "request_id       VARCHAR(64) NOT NULL,"
                + "biz_no           VARCHAR(64) NOT NULL,"
                + "reconcile_count  INT         NOT NULL,"
                + "fail_reason      VARCHAR(255),"
                + "created_at       BIGINT      NOT NULL,"
                + "updated_at       BIGINT      NOT NULL,"
                + "CONSTRAINT uk_withdraw_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_withdraw_status", "withdraw_order", "status"));

        // ST-5 商户账单
        out.add("CREATE TABLE IF NOT EXISTS merchant_bill ("
                + "id           VARCHAR(32) PRIMARY KEY,"
                + "merchant_id  VARCHAR(32) NOT NULL,"
                + "biz_date     BIGINT      NOT NULL,"
                + "bill_type    VARCHAR(16) NOT NULL,"
                + "gross        BIGINT      NOT NULL,"
                + "fee          BIGINT      NOT NULL,"
                + "net          BIGINT      NOT NULL,"
                + "refund       BIGINT      NOT NULL,"
                + "share        BIGINT      NOT NULL,"
                + "record_count INT         NOT NULL,"
                + "file_path    VARCHAR(255),"
                + "status       VARCHAR(16) NOT NULL,"
                + "created_at   BIGINT      NOT NULL,"
                + "CONSTRAINT uk_merchant_bill UNIQUE (merchant_id, biz_date, bill_type))" + tail);

        // I-3 ShedLock 锁表（本域首次注册 JobHandler；与 ShedLockSchemaInitializer 同构，提前建好）
        out.add("CREATE TABLE IF NOT EXISTS shedlock ("
                + "name       VARCHAR(64)  NOT NULL PRIMARY KEY,"
                + "lock_until BIGINT       NOT NULL,"
                + "locked_at  BIGINT       NOT NULL,"
                + "locked_by  VARCHAR(255) NOT NULL)" + tail);

        // ---------------- ST-6 保证金/垫资（P2 G 泳道，全部单库不分片） ----------------
        // request_id 是全局幂等键（分片后 DB 唯一键无法跨片保证）；缴存单/垫资单含 CAS 状态机 UPDATE
        // （影子写只 INSERT，分片会破坏回滚一致性）⇒ 与 ST-1~ST-5 同一归属口径。
        out.add("CREATE TABLE IF NOT EXISTS deposit_record ("
                + "id              VARCHAR(32) PRIMARY KEY,"
                + "merchant_id     VARCHAR(32) NOT NULL,"
                + "account_id      VARCHAR(32) NOT NULL,"
                + "type            VARCHAR(16) NOT NULL,"
                + "ref_id          VARCHAR(32),"
                + "amount          BIGINT      NOT NULL,"
                + "released_amount BIGINT      NOT NULL,"
                + "deducted_amount BIGINT      NOT NULL,"
                + "status          VARCHAR(16) NOT NULL,"
                + "biz_no          VARCHAR(64) NOT NULL,"
                + "request_id      VARCHAR(64) NOT NULL,"
                + "operator        VARCHAR(64),"
                + "reason          VARCHAR(256),"
                + "target_account  VARCHAR(32),"
                + "advance_id      VARCHAR(32),"
                + "created_at      BIGINT      NOT NULL,"
                + "CONSTRAINT uk_deposit_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_deposit_merchant", "deposit_record", "merchant_id"));

        out.add("CREATE TABLE IF NOT EXISTS credit_account ("
                + "merchant_id  VARCHAR(32) PRIMARY KEY,"
                + "credit_limit BIGINT      NOT NULL,"
                + "used         BIGINT      NOT NULL,"
                + "status       VARCHAR(16) NOT NULL,"
                + "version      INT         NOT NULL,"
                + "operator     VARCHAR(64),"
                + "created_at   BIGINT      NOT NULL,"
                + "updated_at   BIGINT      NOT NULL)" + tail);

        out.add("CREATE TABLE IF NOT EXISTS advance_record ("
                + "id               VARCHAR(32) PRIMARY KEY,"
                + "merchant_id      VARCHAR(32) NOT NULL,"
                + "account_id       VARCHAR(32) NOT NULL,"
                + "type             VARCHAR(16) NOT NULL,"
                + "ref_id           VARCHAR(32),"
                + "amount           BIGINT      NOT NULL,"
                + "recovered_amount BIGINT      NOT NULL,"
                + "status           VARCHAR(16) NOT NULL,"
                + "biz_no           VARCHAR(64) NOT NULL,"
                + "request_id       VARCHAR(64) NOT NULL,"
                + "operator         VARCHAR(64),"
                + "reason           VARCHAR(256),"
                + "created_at       BIGINT      NOT NULL,"
                + "updated_at       BIGINT      NOT NULL,"
                + "CONSTRAINT uk_advance_request UNIQUE (request_id))" + tail);
        out.add(index(h2, "idx_advance_merchant", "advance_record", "merchant_id"));

        // ST-x 取号序列（仅 local / 未装配全局发号时使用；docker 分片态走雪花 IdGenerator）。
        // MySQL 必须先 INSERT IGNORE 种子行，否则 next_value_for 的 UPDATE 命中 0 行会返回重号。
        out.add(h2 ? "CREATE SEQUENCE IF NOT EXISTS seq_settle_st START WITH 1"
                : "INSERT IGNORE INTO sequences(name, value) VALUES ('seq_settle_st', 0)");
        return out;
    }

    private static String index(boolean h2, String name, String table, String column) {
        return h2 ? "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + column + ")"
                : "CREATE INDEX " + name + " ON " + table + " (" + column + ")";
    }

    private static boolean isDuplicateIndex(SQLException e) {
        return e.getErrorCode() == 1061
                || (e.getMessage() != null && e.getMessage().contains("Duplicate key name"));
    }

    private SettleSchema() {
    }
}