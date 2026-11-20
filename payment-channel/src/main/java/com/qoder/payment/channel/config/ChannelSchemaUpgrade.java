package com.qoder.payment.channel.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 渠道库增量升级（幂等、只增不改，双 profile 同构）。
 *
 * <p>CH-1：channel_trade 异步列 + channel_receipt_log；
 * CH-2a/2b/3/4/5：channel_trade.channel_code + channel_config / channel_daily_usage /
 * channel_route_rule / channel_bill / channel_bill_detail / sandbox_order。
 *
 * <p>local：schema.sql 已用 {@code ADD COLUMN IF NOT EXISTS} / {@code CREATE TABLE IF NOT EXISTS} 建好，
 * 本升级为空操作；docker：{@code sql.init.mode=never} 不重跑建表脚本，现存 pp_channel 由本升级补齐，
 * 无需手工 ALTER、不改 ops-pro。
 *
 * <p>在 Bean 初始化阶段执行（早于 Dubbo 服务导出与配置 seeding），杜绝「服务已接流量但列尚未补齐」窗口。
 * <b>绝不 UPDATE/DELETE/DROP 既有数据</b>；失败即启动失败（避免缺列导致渠道写入静默出错）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelSchemaUpgrade implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        String product = jdbc.execute((ConnectionCallback<String>) con -> con.getMetaData().getDatabaseProductName());
        boolean h2 = product != null && product.toUpperCase().contains("H2");
        String lob = h2 ? "CLOB" : "LONGTEXT";

        // ---------- CH-1 ----------
        addColumn(h2, "channel_trade", "channel_order_no", "VARCHAR(64)");
        addColumn(h2, "channel_trade", "submitted_at", "BIGINT");
        addColumn(h2, "channel_trade", "receipt_at", "BIGINT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_receipt_log ("
                + "id               BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "biz_no           VARCHAR(64) NOT NULL,"
                + "channel_order_no VARCHAR(64),"
                + "status           VARCHAR(16) NOT NULL,"
                + "raw_payload      " + lob + ","
                + "created_at       BIGINT      NOT NULL,"
                + "CONSTRAINT uk_channel_receipt UNIQUE (biz_no, status)"
                + ")");

        // ---------- CH-2a：受理渠道落痕 + 渠道配置 ----------
        addColumn(h2, "channel_trade", "channel_code", "VARCHAR(32)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_config ("
                + "channel_code      VARCHAR(32) PRIMARY KEY,"
                + "name              VARCHAR(128) NOT NULL,"
                + "adapter_type      VARCHAR(32)  NOT NULL,"
                + "status            VARCHAR(16)  NOT NULL,"
                + "env               VARCHAR(16)  NOT NULL,"
                + "fee_bps           INT,"
                + "weight            INT,"
                + "single_limit      BIGINT,"
                + "daily_limit       BIGINT,"
                + "maintenance_start BIGINT,"
                + "maintenance_end   BIGINT,"
                + "config            " + lob + ","
                + "created_at        BIGINT       NOT NULL,"
                + "updated_at        BIGINT)");

        // ---------- CH-5：日累计 ----------
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_daily_usage ("
                + "channel_code VARCHAR(32) NOT NULL,"
                + "biz_date     VARCHAR(8)  NOT NULL,"
                + "used_amount  BIGINT      NOT NULL,"
                + "used_count   BIGINT      NOT NULL,"
                + "CONSTRAINT uk_channel_daily_usage UNIQUE (channel_code, biz_date))");

        // ---------- CH-3：路由规则 ----------
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_route_rule ("
                + "id           BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "priority     INT         NOT NULL,"
                + "min_amount   BIGINT,"
                + "max_amount   BIGINT,"
                + "card_bin     VARCHAR(16),"
                + "channel_code VARCHAR(32) NOT NULL,"
                + "weight       INT,"
                + "status       VARCHAR(16) NOT NULL,"
                + "version      INT,"
                + "created_at   BIGINT      NOT NULL)");

        // ---------- CH-4：账单主表 + 明细 ----------
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_bill ("
                + "id            BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "channel_code  VARCHAR(32)  NOT NULL,"
                + "biz_date      VARCHAR(8)   NOT NULL,"
                + "file_path     VARCHAR(512),"
                + "total_count   BIGINT,"
                + "total_amount  BIGINT,"
                + "status        VARCHAR(16)  NOT NULL,"
                + "parsed_count  BIGINT,"
                + "parsed_amount BIGINT,"
                + "matched_count BIGINT,"
                + "diff_count    BIGINT,"
                + "dispute_count BIGINT,"
                + "message       VARCHAR(1024),"
                + "created_at    BIGINT       NOT NULL,"
                + "parsed_at     BIGINT,"
                + "CONSTRAINT uk_channel_bill UNIQUE (channel_code, biz_date))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS channel_bill_detail ("
                + "id               BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "bill_id          BIGINT      NOT NULL,"
                + "channel_order_no VARCHAR(64) NOT NULL,"
                + "biz_no           VARCHAR(64) NOT NULL,"
                + "type             VARCHAR(16) NOT NULL,"
                + "amount           BIGINT      NOT NULL,"
                + "status           VARCHAR(16) NOT NULL,"
                + "raw              " + lob + ","
                + "match_result     VARCHAR(32),"
                + "dispute_id       BIGINT,"
                + "present          INT DEFAULT 1,"
                + "CONSTRAINT uk_channel_bill_detail UNIQUE (bill_id, channel_order_no))");
        addColumn(h2, "channel_bill_detail", "present", "INT DEFAULT 1");

        // ---------- CH-2b：沙箱银行侧账本 ----------
        jdbc.execute("CREATE TABLE IF NOT EXISTS sandbox_order ("
                + "id               BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "channel_order_no VARCHAR(64) NOT NULL,"
                + "biz_no           VARCHAR(64) NOT NULL,"
                + "type             VARCHAR(16) NOT NULL,"
                + "amount           BIGINT      NOT NULL,"
                + "requested_amount BIGINT,"
                + "status           VARCHAR(16) NOT NULL,"
                + "fault            VARCHAR(128),"
                + "biz_date         VARCHAR(8),"
                + "due_at           BIGINT,"
                + "created_at       BIGINT      NOT NULL,"
                + "finalized_at     BIGINT,"
                + "receipt_sent     INT DEFAULT 0,"
                + "attempts         INT DEFAULT 0,"
                + "CONSTRAINT uk_sandbox_order_no UNIQUE (channel_order_no),"
                + "CONSTRAINT uk_sandbox_biz UNIQUE (biz_no, type))");

        // ---------- CH-6/X-4：签约代扣（只增不改；协议/扣款单/事件 outbox/审计） ----------
        jdbc.execute("CREATE TABLE IF NOT EXISTS sign_contract ("
                + "id                BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "sign_no           VARCHAR(40)  NOT NULL,"
                + "account_id        VARCHAR(40)  NOT NULL,"
                + "merchant_id       VARCHAR(40)  NOT NULL,"
                + "channel_code      VARCHAR(32)  NOT NULL,"
                + "channel_sign_no   VARCHAR(64),"
                + "card_no_hash      VARCHAR(64)  NOT NULL,"
                + "card_no_mask      VARCHAR(32)  NOT NULL,"
                + "card_no_cipher    VARCHAR(512) NOT NULL,"
                + "status            VARCHAR(16)  NOT NULL,"
                + "single_limit      BIGINT,"
                + "period_limit      BIGINT,"
                + "period_type       VARCHAR(8),"
                + "effective_at      BIGINT       NOT NULL,"
                + "expired_at        BIGINT       NOT NULL,"
                + "deduct_day        INT,"
                + "max_retry         INT          NOT NULL,"
                + "retry_interval_ms BIGINT       NOT NULL,"
                + "callback_url      VARCHAR(512),"
                + "app_id            VARCHAR(64),"
                + "request_id        VARCHAR(80)  NOT NULL,"
                + "authorized_scope  " + lob + ","
                + "active_key        VARCHAR(160),"
                + "signed_at         BIGINT,"
                + "cancelled_at      BIGINT,"
                + "cancel_reason     VARCHAR(256),"
                + "created_at        BIGINT       NOT NULL,"
                + "updated_at        BIGINT,"
                + "CONSTRAINT uk_sign_no UNIQUE (sign_no),"
                + "CONSTRAINT uk_sign_request UNIQUE (request_id),"
                + "CONSTRAINT uk_sign_active UNIQUE (active_key))");
        addIndex(h2, "sign_contract", "idx_sign_account", "account_id");
        addIndex(h2, "sign_contract", "idx_sign_merchant", "merchant_id");
        addIndex(h2, "sign_contract", "idx_sign_expired", "status, expired_at");

        jdbc.execute("CREATE TABLE IF NOT EXISTS sign_deduct ("
                + "id                 BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "deduct_no          VARCHAR(40) NOT NULL,"
                + "sign_no            VARCHAR(40) NOT NULL,"
                + "account_id         VARCHAR(40),"
                + "merchant_id        VARCHAR(40),"
                + "request_id         VARCHAR(80) NOT NULL,"
                + "attempt_request_id VARCHAR(96),"
                + "order_id           VARCHAR(40),"
                + "payment_id         VARCHAR(40),"
                + "amount             BIGINT      NOT NULL,"
                + "status             VARCHAR(16) NOT NULL,"
                + "attempts           INT         NOT NULL,"
                + "max_retry          INT         NOT NULL,"
                + "next_retry_at      BIGINT,"
                + "fail_reason        VARCHAR(512),"
                + "period_key         VARCHAR(8),"
                + "channel_code       VARCHAR(32),"
                + "created_at         BIGINT      NOT NULL,"
                + "updated_at         BIGINT,"
                + "finished_at        BIGINT,"
                + "CONSTRAINT uk_sign_deduct_no UNIQUE (deduct_no),"
                + "CONSTRAINT uk_sign_deduct_request UNIQUE (request_id))");
        addIndex(h2, "sign_deduct", "idx_sign_deduct_sign", "sign_no, period_key, status");
        addIndex(h2, "sign_deduct", "idx_sign_deduct_retry", "status, next_retry_at");

        jdbc.execute("CREATE TABLE IF NOT EXISTS sign_event ("
                + "id            BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "event         VARCHAR(32) NOT NULL,"
                + "biz_id        VARCHAR(64) NOT NULL,"
                + "sign_no       VARCHAR(40) NOT NULL,"
                + "deduct_no     VARCHAR(40),"
                + "payload       " + lob + ","
                + "status        VARCHAR(16) NOT NULL,"
                + "attempts      INT         NOT NULL,"
                + "notify_id     VARCHAR(40),"
                + "last_error    VARCHAR(512),"
                + "created_at    BIGINT      NOT NULL,"
                + "sent_at       BIGINT,"
                + "next_retry_at BIGINT,"
                + "CONSTRAINT uk_sign_event UNIQUE (event, biz_id))");
        addIndex(h2, "sign_event", "idx_sign_event_dispatch", "status, next_retry_at");

        jdbc.execute("CREATE TABLE IF NOT EXISTS sign_audit ("
                + "id         BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "sign_no    VARCHAR(40),"
                + "deduct_no  VARCHAR(40),"
                + "action     VARCHAR(32) NOT NULL,"
                + "operator   VARCHAR(64),"
                + "request_id VARCHAR(96),"
                + "amount     BIGINT,"
                + "result     VARCHAR(16),"
                + "reason     VARCHAR(512),"
                + "created_at BIGINT      NOT NULL)");
        addIndex(h2, "sign_audit", "idx_sign_audit_sign", "sign_no, id");

        log.info("渠道库增量校验完成 product={} tables=channel_config/channel_daily_usage/channel_route_rule/"
                + "channel_bill/channel_bill_detail/sandbox_order/sign_contract/sign_deduct/sign_event/sign_audit "
                + "asyncReceiptLog=ready", product);
    }

    /** 索引幂等补齐（H2 用 IF NOT EXISTS；MySQL 查 information_schema.statistics，绝不重复建/删既有索引）。 */
    private void addIndex(boolean h2, String table, String index, String columns) {
        if (h2) {
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + "(" + columns + ")");
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND index_name=?", Integer.class, table, index);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("CREATE INDEX " + index + " ON " + table + "(" + columns + ")");
        log.info("渠道库补索引 {}.{} ({})", table, index, columns);
    }

    private void addColumn(boolean h2, String table, String column, String type) {
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
        log.info("渠道库补列 {}.{} {}", table, column, type);
    }
}