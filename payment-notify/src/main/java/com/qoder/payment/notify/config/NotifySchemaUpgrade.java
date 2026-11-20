package com.qoder.payment.notify.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * N-4 通知表 additive 升级（双 profile 同构、幂等、只增不改）。
 *
 * <p>为什么需要运行期补列：docker {@code sql.init.mode=never} 不重跑 schema-mysql.sql，
 * 现存 pp_notify 库必须在启动时补 {@code resend_count}；local H2 由 schema.sql 的
 * {@code ADD COLUMN IF NOT EXISTS} 覆盖，本类再校验一次（幂等）。
 *
 * <p><b>数据保护</b>：只 ADD COLUMN / CREATE INDEX，绝不 DROP/TRUNCATE/DELETE/UPDATE 既有行。
 */
@Slf4j
@Component
public class NotifySchemaUpgrade implements InitializingBean {

    private final DataSource dataSource;

    public NotifySchemaUpgrade(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(true);
            String product = con.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            if (!columnExists(con, "notify_record", "resend_count")) {
                execute(con, h2
                        ? "ALTER TABLE notify_record ADD COLUMN IF NOT EXISTS resend_count INT NOT NULL DEFAULT 0"
                        : "ALTER TABLE notify_record ADD COLUMN resend_count INT NOT NULL DEFAULT 0");
                log.info("N-4 notify_record.resend_count 补列完成 product={}", product);
            }
            if (!indexExists(con, "notify_record", "idx_notify_app_created")) {
                try {
                    execute(con, "CREATE INDEX idx_notify_app_created ON notify_record (app_id, created_at)");
                } catch (SQLException e) {
                    log.warn("N-4 通知查询索引创建跳过（可能已存在）: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            // 补列失败不阻断启动：查询/重发会明确报错，既有推送链路不受影响
            log.error("N-4 通知表 additive 升级失败（既有推送不受影响）: {}", e.getMessage());
        }
    }

    private static void execute(Connection con, String ddl) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(ddl);
        }
    }

    private static boolean columnExists(Connection con, String table, String column) throws SQLException {
        DatabaseMetaData meta = con.getMetaData();
        for (String candidate : new String[]{table, table.toUpperCase(), table.toLowerCase()}) {
            try (ResultSet rs = meta.getColumns(con.getCatalog(), null, candidate, null)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection con, String table, String index) throws SQLException {
        DatabaseMetaData meta = con.getMetaData();
        for (String candidate : new String[]{table, table.toUpperCase(), table.toLowerCase()}) {
            try (ResultSet rs = meta.getIndexInfo(con.getCatalog(), null, candidate, false, true)) {
                while (rs.next()) {
                    if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}