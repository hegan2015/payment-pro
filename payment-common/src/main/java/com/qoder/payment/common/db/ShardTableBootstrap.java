package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-5a 分片表初始化 + <b>additive 双向影子复制</b>（数据保护红线的落地实现）。
 *
 * <p>只做三件事，<b>绝不 DROP / TRUNCATE / DELETE / UPDATE 任何既有行</b>：
 * <ol>
 *   <li>{@code CREATE TABLE IF NOT EXISTS <逻辑表>_<k> LIKE <逻辑表>}：只新建分片表（含原表唯一键/索引）；</li>
 *   <li>原单表 → 分片表：把既有行按分片键复制过去，使分片期查询结果与单库一致（原表原行全部保留）；</li>
 *   <li>分片表 → 原单表：把分片期新增行补回原单表，使<b>回滚（enabled=false）后结果与切前一致</b>。</li>
 * </ol>
 *
 * <p>复制用「主键冲突即跳过」实现幂等（可反复启动），不覆盖既有行。原单库永久保留，
 * §5.1 步骤⑥「下线单库」不做。
 *
 * <p>必须在 ShardingSphere DataSource 创建<b>之前</b>执行：分片表不存在时其元数据加载会失败。
 */
public final class ShardTableBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ShardTableBootstrap.class);

    public static Map<String, Object> bootstrap(DataSource raw, Map<String, String> tables, int shardCount) {
        // 启动期 DDL（建分片表 + additive 补列）在多域并发重启时会遇 MySQL 元数据锁争用/瞬时 stall，
        // 表现为 Hikari 取连接失败或 "No operations allowed after connection closed"。
        // 这类瞬时故障不应让域永久起不来：有界重试（每次取新连接），仍失败才 fail-fast。
        SQLException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return doBootstrap(raw, tables, shardCount);
            } catch (SQLException e) {
                last = e;
                if (!isTransient(e) || attempt == 3) {
                    break;
                }
                long wait = attempt * 3000L;
                log.warn("I-5a 分片表初始化遇瞬时连接故障，{}ms 后重试（{}/3）: {}", wait, attempt, e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("I-5a 分片表初始化失败（docker 部署前置：先执行 schema-mysql.sql "
                + "与 ops-pro/deploy/payment-sharding-tables.sql）: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    /** 瞬时连接类故障可重试；SQL 语法/列不存在等确定性错误不重试（避免掩盖真问题）。 */
    private static boolean isTransient(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage()).toLowerCase();
            if (m.contains("connection closed") || m.contains("communications link failure")
                    || m.contains("connection is not available") || m.contains("connection refused")
                    || m.contains("lock wait timeout") || m.contains("deadlock")
                    || m.contains("too many connections") || m.contains("query execution was interrupted")) {
                return true;
            }
            if (t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLTimeoutException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> doBootstrap(DataSource raw, Map<String, String> tables, int shardCount)
            throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Connection con = raw.getConnection()) {
            con.setAutoCommit(true);
            String product = con.getMetaData().getDatabaseProductName();
            if (product == null || !product.toUpperCase().contains("MYSQL")) {
                throw new IllegalStateException("I-5a 分片试点仅支持 docker MySQL，当前=" + product
                        + "（local 请置 payment.sharding.enabled=false）");
            }
            for (Map.Entry<String, String> entry : tables.entrySet()) {
                stats.put(entry.getKey(), initTable(con, entry.getKey(), entry.getValue(), shardCount));
            }
        }
        log.info("I-5a 分片表就绪（只新建/只复制，既有数据零删除）: {}", stats);
        return stats;
    }

    private static Map<String, Object> initTable(Connection con, String logicTable, String shardColumn, int shardCount)
            throws SQLException {
        long legacyRows = count(con, logicTable);
        int addedColumns = 0;
        Map<String, Long> shardRows = new LinkedHashMap<>();
        for (int k = 0; k < shardCount; k++) {
            String physical = ShardKeys.physicalTable(logicTable, k);
            try (Statement st = con.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + physical + " LIKE " + logicTable);
            }
            // 业务域后续 additive 加列（如 payment.pay_mode）只改主表时，分片表会缺列，
            // 复制行即报 Unknown column 致 dataSource 建不起来。此处双向补列（只 ADD，绝不 DROP/MODIFY）。
            addedColumns += alignColumns(con, logicTable, physical);
            shardRows.put(physical, count(con, physical));
        }
        if (shardCount > 0) {
            addedColumns += alignColumns(con, ShardKeys.physicalTable(logicTable, 0), logicTable);
        }
        int toShards = copyOutToShards(con, logicTable, shardColumn, shardCount);
        int toLegacy = copyShardsToLegacy(con, logicTable, shardCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardColumn", shardColumn);
        out.put("addedColumns", addedColumns);
        out.put("legacyRowsBefore", legacyRows);
        out.put("shardRowsBefore", shardRows);
        out.put("copiedToShards", toShards);
        out.put("copiedToLegacy", toLegacy);
        out.put("legacyRowsAfter", count(con, logicTable));
        Map<String, Long> after = new LinkedHashMap<>();
        for (int k = 0; k < shardCount; k++) {
            String physical = ShardKeys.physicalTable(logicTable, k);
            after.put(physical, count(con, physical));
        }
        out.put("shardRowsAfter", after);
        return out;
    }

    /**
     * additive 补列：把 source 有而 target 没有的列 ADD 到 target（<b>只 ADD，绝不 DROP/MODIFY/DELETE</b>）。
     * 统一以可空列添加，避免既有行在严格模式下因 NOT NULL 无默认值而失败；源列有显式默认值则沿用。
     */
    private static int alignColumns(Connection con, String source, String target) throws SQLException {
        Map<String, String[]> src = columns(con, source);
        Map<String, String[]> dst = columns(con, target);
        int added = 0;
        for (Map.Entry<String, String[]> e : src.entrySet()) {
            if (dst.containsKey(e.getKey())) {
                continue;
            }
            String[] def = e.getValue();
            String type = def[0];
            String extra = def[2];
            if (extra != null && extra.toUpperCase().contains("AUTO_INCREMENT")) {
                log.warn("分片补列跳过自增列 {}.{}（需键定义，交由 schema 脚本处理）", target, e.getKey());
                continue;
            }
            StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(target)
                    .append(" ADD COLUMN `").append(e.getKey()).append("` ").append(type).append(" NULL");
            if (def[1] != null) {
                ddl.append(" DEFAULT ").append(literal(def[1], type));
            }
            try (Statement st = con.createStatement()) {
                st.execute(ddl.toString());
            }
            added++;
            log.info("分片 additive 补列 {}.{} {}", target, e.getKey(), type);
        }
        return added;
    }

    /** SHOW COLUMNS → field → [type, default, extra]。 */
    private static Map<String, String[]> columns(Connection con, String table) throws SQLException {
        Map<String, String[]> out = new LinkedHashMap<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + table)) {
            while (rs.next()) {
                out.put(rs.getString("Field"),
                        new String[]{rs.getString("Type"), rs.getString("Default"), rs.getString("Extra")});
            }
        }
        return out;
    }

    private static String literal(String value, String type) {
        String t = type.toLowerCase();
        boolean numeric = t.startsWith("int") || t.startsWith("bigint") || t.startsWith("decimal")
                || t.startsWith("double") || t.startsWith("float") || t.startsWith("smallint") || t.startsWith("tinyint");
        if (numeric || value.equalsIgnoreCase("CURRENT_TIMESTAMP") || value.equalsIgnoreCase("NULL")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** 原单表 → 各分片表（按分片键路由，与 SQL 侧算法同一函数）。 */
    private static int copyOutToShards(Connection con, String logicTable, String shardColumn, int shardCount)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        Map<Integer, List<Object[]>> buckets = new LinkedHashMap<>();
        for (int k = 0; k < shardCount; k++) {
            buckets.put(k, new ArrayList<>());
        }
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + logicTable)) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                cols.add(meta.getColumnLabel(i));
            }
            while (rs.next()) {
                Object key = rs.getObject(shardColumn);
                int shard = ShardKeys.shardOf(key == null ? null : String.valueOf(key), shardCount);
                buckets.get(shard).add(row(rs, meta.getColumnCount()));
            }
        }
        int inserted = 0;
        for (int k = 0; k < shardCount; k++) {
            inserted += insertAll(con, ShardKeys.physicalTable(logicTable, k), cols, buckets.get(k));
        }
        return inserted;
    }

    /** 各分片表 → 原单表（回滚一致性保障）。 */
    private static int copyShardsToLegacy(Connection con, String logicTable, int shardCount) throws SQLException {
        int inserted = 0;
        for (int k = 0; k < shardCount; k++) {
            String physical = ShardKeys.physicalTable(logicTable, k);
            List<String> cols = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + physical)) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    cols.add(meta.getColumnLabel(i));
                }
                while (rs.next()) {
                    rows.add(row(rs, meta.getColumnCount()));
                }
            }
            inserted += insertAll(con, logicTable, cols, rows);
        }
        return inserted;
    }

    private static Object[] row(ResultSet rs, int columns) throws SQLException {
        Object[] values = new Object[columns];
        for (int i = 0; i < columns; i++) {
            values[i] = rs.getObject(i + 1);
        }
        return values;
    }

    /** 只 INSERT；主键/唯一键冲突即跳过（幂等、绝不覆盖既有行）。 */
    private static int insertAll(Connection con, String target, List<String> cols, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(target).append('(');
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ",").append(cols.get(i));
        }
        sql.append(") VALUES(");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');
        int inserted = 0;
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (Object[] values : rows) {
                for (int i = 0; i < values.length; i++) {
                    ps.setObject(i + 1, values[i]);
                }
                try {
                    inserted += ps.executeUpdate();
                } catch (SQLException dup) {
                    if (!isDuplicate(dup)) {
                        throw dup;
                    }
                }
            }
        }
        return inserted;
    }

    static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return e.getErrorCode() == 1062 || (state != null && state.startsWith("23"));
    }

    private static long count(Connection con, String table) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private ShardTableBootstrap() {
    }
}