package com.qoder.payment.cashier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * P1 C-2/C-3 收银台建表（<b>单一 DDL 事实来源</b>，幂等、只增不改，双 profile 同构）。
 *
 * <p>local：schema.sql 已建好，本类幂等空操作；docker：{@code sql.init.mode=never} 不重跑脚本，
 * pp_cashier 库（url {@code createDatabaseIfNotExist=true} 首连自动建库）由本类启动期补建，
 * 无需手工执行、不改 ops-pro。cashier 无分片，直连原库。
 *
 * <p><b>数据保护</b>：只 CREATE TABLE IF NOT EXISTS / INSERT IGNORE 种子，绝不 DROP/TRUNCATE/DELETE/UPDATE 既有行。
 * 码值/会话 ID 由 Java SecureRandom/UUID 生成，seq_cashier 仅按 §0.6 约定保留（当前未用于取号）。
 */
public final class CashierSchema {

    private static final Logger log = LoggerFactory.getLogger(CashierSchema.class);

    public static void ensure(DataSource ds) {
        try (Connection con = ds.getConnection()) {
            con.setAutoCommit(true);
            String product = con.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            for (String ddl : statements(h2)) {
                try (Statement st = con.createStatement()) {
                    st.execute(ddl);
                } catch (SQLException e) {
                    if (ddl.startsWith("CREATE INDEX") && isDuplicateIndex(e)) {
                        continue;
                    }
                    throw e;
                }
            }
            log.info("C-2/C-3 收银台表校验完成 product={} tables=[pay_code,pay_credential,verify_session,sms_code]（只增不改）",
                    product);
        } catch (SQLException e) {
            throw new IllegalStateException("C-2/C-3 收银台建表失败（只增不改，失败即启动失败避免静默错账）: "
                    + e.getMessage(), e);
        }
    }

    private static List<String> statements(boolean h2) {
        String tail = h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        List<String> out = new ArrayList<>();

        // 取号序列（§0.6 约定保留；当前码值/会话 ID 走 Java 生成，未调用 next_value_for）
        out.add(h2 ? "CREATE SEQUENCE IF NOT EXISTS seq_cashier START WITH 1"
                : "CREATE TABLE IF NOT EXISTS sequences (name VARCHAR(64) PRIMARY KEY, value BIGINT NOT NULL)" + tail);
        if (!h2) {
            out.add("INSERT IGNORE INTO sequences(name, value) VALUES ('seq_cashier', 0)");
        }

        out.add("CREATE TABLE IF NOT EXISTS pay_code ("
                + "code VARCHAR(64) PRIMARY KEY,"
                + "type VARCHAR(24) NOT NULL,"
                + "account_id VARCHAR(32),"
                + "order_id VARCHAR(32),"
                + "amount BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "expire_at BIGINT NOT NULL,"
                + "request_id VARCHAR(64),"
                + "consume_request_id VARCHAR(64),"
                + "qr_content VARCHAR(512),"
                + "merchant_name VARCHAR(128),"
                + "subject VARCHAR(256),"
                + "created_at BIGINT NOT NULL,"
                + "consumed_at BIGINT)" + tail);
        out.add(index(h2, "idx_pay_code_order", "pay_code", "order_id"));
        out.add(index(h2, "idx_pay_code_status", "pay_code", "status, expire_at"));
        out.add(index(h2, "idx_pay_code_req", "pay_code", "request_id"));

        out.add("CREATE TABLE IF NOT EXISTS pay_credential ("
                + "member_id VARCHAR(32) PRIMARY KEY,"
                + "password_hash VARCHAR(128) NOT NULL,"
                + "salt VARCHAR(64) NOT NULL,"
                + "fail_count INT NOT NULL,"
                + "locked_until BIGINT,"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL)" + tail);

        out.add("CREATE TABLE IF NOT EXISTS verify_session ("
                + "id VARCHAR(64) PRIMARY KEY,"
                + "member_id VARCHAR(32),"
                + "account_id VARCHAR(32),"
                + "order_id VARCHAR(32),"
                + "amount BIGINT,"
                + "verify_type VARCHAR(24) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "expire_at BIGINT NOT NULL,"
                + "request_id VARCHAR(64),"
                + "created_at BIGINT NOT NULL,"
                + "used_at BIGINT)" + tail);
        out.add(index(h2, "idx_verify_session_req", "verify_session", "request_id"));

        out.add("CREATE TABLE IF NOT EXISTS sms_code ("
                + "id VARCHAR(64) PRIMARY KEY,"
                + "member_id VARCHAR(32),"
                + "account_id VARCHAR(32),"
                + "order_id VARCHAR(32),"
                + "code VARCHAR(8) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "expire_at BIGINT NOT NULL,"
                + "created_at BIGINT NOT NULL)" + tail);
        out.add(index(h2, "idx_sms_member", "sms_code", "member_id, status"));
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

    private CashierSchema() {
    }
}