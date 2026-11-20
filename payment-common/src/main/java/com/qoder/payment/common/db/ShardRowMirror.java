package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * I-5b 资金域全行双写 + 影子读比对（I-5a ShardMirror 的资金域增强版，新增类不改 I-5a）。
 *
 * <p>为什么不能只用 I-5a 的 additive INSERT 镜像：settle_record / recon_report 是 append-only，
 * 只 INSERT 即可两侧一致；而资金域的 account_balance（余额/冻结/version）、payment（状态/xid/已退金额）、
 * trade_order（状态/payment_id）、refund_record（状态/xid）都是可变行。只 INSERT 会让影子侧停留在旧值，
 * 一键回滚后余额/状态与切前不一致 —— 这是不可接受的资金风险。
 *
 * <p>本类的做法（严格受限的双写）：
 * <ol>
 *   <li>业务事务 afterCommit 之后，按主键从权威侧读回整行真值；</li>
 *   <li>把这一行原样写进影子侧（分片态→原单表；单库态→对应分片表）：
 *       主键已存在则只 UPDATE 非主键列，不存在则 INSERT；</li>
 *   <li>绝不 DELETE / DROP / TRUNCATE 任何行；绝不改写权威侧；
 *       主键不存在但其它唯一键冲突时拒绝写入并计数（绝不把他行改成别的业务单）。</li>
 * </ol>
 * 影子侧写入的值就是业务刚刚提交的真实值，因此「双写期数据一致 → 可直接回滚」成立。
 *
 * <p>镜像失败只记 ERROR + 计数（不影响已提交业务写）；syncRaw 提供全量修复通道，
 * compareRaw 提供逐行逐列比对（差异数大于 0 即禁止切流/回滚，由端点与验证脚本执行）。
 *
 * <p>单库分表方案下权威侧与影子侧在同一个 MySQL 库，故跨片写仍是同一本地事务（原子性不破坏）；
 * 镜像是提交后的补偿写，失败可由启动同步 / resync 修复，比对为最终一致性的验收闸门。
 */
public class ShardRowMirror {

    private static final Logger log = LoggerFactory.getLogger(ShardRowMirror.class);

    /** 分片表规格：逻辑表名 / 主键列 / 分片键列。 */
    public record Spec(String table, String pk, String shardColumn) {
    }

    private final DataSource raw;
    private final DataSource routed;
    private final ShardRuntime runtime;
    private final Map<String, Spec> specs;

    private final AtomicLong mirrorRows = new AtomicLong();
    private final AtomicLong mirrorFailures = new AtomicLong();
    private final AtomicLong mirrorConflicts = new AtomicLong();

    public ShardRowMirror(DataSource raw, DataSource routed, ShardRuntime runtime, List<Spec> specs) {
        this.raw = raw;
        this.routed = routed;
        this.runtime = runtime;
        Map<String, Spec> map = new LinkedHashMap<>();
        for (Spec s : specs) {
            map.put(s.table(), s);
        }
        this.specs = map;
    }

    public Map<String, Spec> specs() {
        return specs;
    }

    public ShardRuntime runtime() {
        return runtime;
    }

    // ------------------------------------------------------------------ 双写

    /**
     * 登记一行双写：事务提交后把权威侧整行真值同步到影子侧。
     * 同一事务内同表同主键只登记一次（取提交后的最终值，避免中间态覆盖）。
     */
    public void mirror(String table, Object pkValue) {
        if (!runtime.mirrorLegacy() || pkValue == null || !specs.containsKey(table)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doMirror(table, pkValue);
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> existing = (Set<String>) TransactionSynchronizationManager.getResource(this);
        final Set<String> pending = existing == null ? new LinkedHashSet<>() : existing;
        if (existing == null) {
            TransactionSynchronizationManager.bindResource(this, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String key : new ArrayList<>(pending)) {
                        int at = key.indexOf('#');
                        doMirror(key.substring(0, at), key.substring(at + 1));
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(ShardRowMirror.this);
                }
            });
        }
        pending.add(table + "#" + pkValue);
    }

    private void doMirror(String table, Object pkValue) {
        Spec spec = specs.get(table);
        if (spec == null) {
            return;
        }
        try {
            Map<String, Object> row = readAuthoritative(spec, pkValue);
            if (row == null) {
                // 权威侧无此行（理论上不会发生：资金域只增不删）；不臆断、不写影子侧
                mirrorFailures.incrementAndGet();
                log.error("I-5b 双写跳过：权威侧无此行 table={} pk={}", table, pkValue);
                return;
            }
            String target = targetTable(spec, row);
            try (Connection con = raw.getConnection()) {
                con.setAutoCommit(true);
                if (upsert(con, spec, target, row)) {
                    mirrorRows.incrementAndGet();
                } else {
                    mirrorConflicts.incrementAndGet();
                    log.error("I-5b 双写唯一键冲突（拒绝改写他行）table={} target={} pk={}", table, target, pkValue);
                }
            }
        } catch (Exception e) {
            mirrorFailures.incrementAndGet();
            log.error("I-5b 双写失败（不影响已提交业务写，resync/启动同步可修复）table={} pk={} err={}",
                    table, pkValue, e.toString());
        }
    }

    /** 权威侧整行真值：分片态经 ShardingSphere 逻辑表读，单库态直读原单表。 */
    private Map<String, Object> readAuthoritative(Spec spec, Object pkValue) throws SQLException {
        DataSource ds = runtime.isActive() ? routed : raw;
        try (Connection con = ds.getConnection()) {
            return readOne(con, spec.table(), spec.pk(), pkValue);
        }
    }

    /** 影子侧物理表名：分片态→原单表；单库态→按分片键定位的分片表。 */
    private String targetTable(Spec spec, Map<String, Object> row) {
        if (runtime.isActive()) {
            return spec.table();
        }
        Object key = row.get(spec.shardColumn());
        return ShardKeys.physicalTable(spec.table(),
                ShardKeys.shardOf(key == null ? null : String.valueOf(key), runtime.shardCount()));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mirrorRows", mirrorRows.get());
        out.put("mirrorFailures", mirrorFailures.get());
        out.put("mirrorConflicts", mirrorConflicts.get());
        out.put("mirrorTables", new ArrayList<>(specs.keySet()));
        return out;
    }

    public long mirrorFailures() {
        return mirrorFailures.get();
    }

    // ------------------------------------------------------- 影子读比对（只读）

    /** 逐行逐列比对：原单表 vs 分片侧（分片态走逻辑表归并，单库态走各物理表并集）。 */
    public Map<String, Object> compare(String table) {
        Spec spec = specs.get(table);
        if (spec == null) {
            return Map.of("error", "未登记的分片表: " + table);
        }
        return compareRaw(raw, routed, runtime.isActive(), spec, runtime.shardCount());
    }

    public Map<String, Object> compareAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Spec spec : specs.values()) {
            Map<String, Object> one = compareRaw(raw, routed, runtime.isActive(), spec, runtime.shardCount());
            out.put(spec.table(), one);
            total += ((Number) one.get("diffTotal")).longValue();
        }
        out.put("diffTotal", total);
        out.put("shardingActive", runtime.isActive());
        return out;
    }

    /** 静态比对通道：启动前置初始化阶段 ShardingSphere 尚未创建，active 传 false 即按物理表并集比对。 */
    public static Map<String, Object> compareRaw(DataSource raw, DataSource routed, boolean active,
                                                 Spec spec, int shardCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", spec.table());
        out.put("pk", spec.pk());
        out.put("shardColumn", spec.shardColumn());
        try {
            Map<String, Map<String, Object>> legacy = byPk(readAll(raw, spec.table()), spec.pk());
            Map<String, Map<String, Object>> shards = byPk(readShardSide(raw, routed, active, spec, shardCount), spec.pk());
            List<String> missingInShards = new ArrayList<>();
            List<String> missingInLegacy = new ArrayList<>();
            List<Map<String, Object>> valueDiffs = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : legacy.entrySet()) {
                Map<String, Object> other = shards.get(e.getKey());
                if (other == null) {
                    missingInShards.add(e.getKey());
                } else {
                    List<String> cols = diffColumns(e.getValue(), other);
                    if (!cols.isEmpty()) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("pk", e.getKey());
                        d.put("columns", cols);
                        valueDiffs.add(d);
                    }
                }
            }
            for (String key : shards.keySet()) {
                if (!legacy.containsKey(key)) {
                    missingInLegacy.add(key);
                }
            }
            out.put("legacyRows", legacy.size());
            out.put("shardRows", shards.size());
            out.put("missingInShards", missingInShards.size());
            out.put("missingInLegacy", missingInLegacy.size());
            out.put("valueDiffs", valueDiffs.size());
            out.put("diffTotal", missingInShards.size() + missingInLegacy.size() + valueDiffs.size());
            out.put("samples", sample(missingInShards, missingInLegacy, valueDiffs));
        } catch (SQLException e) {
            out.put("error", e.getMessage());
            out.put("diffTotal", -1L);
        }
        return out;
    }

    private static List<Map<String, Object>> sample(List<String> a, List<String> b, List<Map<String, Object>> c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, a.size()); i++) {
            out.add(Map.of("kind", "missingInShards", "pk", a.get(i)));
        }
        for (int i = 0; i < Math.min(3, b.size()); i++) {
            out.add(Map.of("kind", "missingInLegacy", "pk", b.get(i)));
        }
        for (int i = 0; i < Math.min(3, c.size()); i++) {
            out.add(c.get(i));
        }
        return out;
    }

    private static List<String> diffColumns(Map<String, Object> a, Map<String, Object> b) {
        List<String> cols = new ArrayList<>();
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!norm(e.getValue()).equals(norm(b.get(e.getKey())))) {
                cols.add(e.getKey());
            }
        }
        for (String key : b.keySet()) {
            if (!a.containsKey(key)) {
                cols.add(key);
            }
        }
        return cols;
    }

    private static String norm(Object v) {
        if (v == null) {
            return "<null>";
        }
        if (v instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(v);
    }

    // --------------------------------------------------------- 全量同步（修复）

    /**
     * 全量同步（只 UPDATE 影子副本 / INSERT 缺行，绝不 DELETE）。
     *
     * @param toLegacy true=分片侧→原单表（回滚前护航）；false=原单表→分片侧（切流前护航）
     */
    public Map<String, Object> sync(String table, boolean toLegacy) {
        Spec spec = specs.get(table);
        if (spec == null) {
            return Map.of("error", "未登记的分片表: " + table);
        }
        return syncRaw(raw, spec, runtime.shardCount(), toLegacy);
    }

    public Map<String, Object> syncAll(boolean toLegacy) {
        Map<String, Object> out = new LinkedHashMap<>();
        long written = 0;
        long conflicts = 0;
        for (Spec spec : specs.values()) {
            Map<String, Object> one = syncRaw(raw, spec, runtime.shardCount(), toLegacy);
            out.put(spec.table(), one);
            written += ((Number) one.get("written")).longValue();
            conflicts += ((Number) one.get("conflicts")).longValue();
        }
        out.put("direction", toLegacy ? "TO_LEGACY" : "TO_SHARDS");
        out.put("written", written);
        out.put("conflicts", conflicts);
        return out;
    }

    /** 静态同步通道（启动前置初始化 / 端点均可用，只依赖原单库连接）。 */
    public static Map<String, Object> syncRaw(DataSource raw, Spec spec, int shardCount, boolean toLegacy) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", spec.table());
        out.put("direction", toLegacy ? "TO_LEGACY" : "TO_SHARDS");
        long written = 0;
        long conflicts = 0;
        long sourceRows = 0;
        try (Connection con = raw.getConnection()) {
            con.setAutoCommit(true);
            List<Map<String, Object>> source;
            if (toLegacy) {
                source = new ArrayList<>();
                for (int k = 0; k < shardCount; k++) {
                    source.addAll(readAll(con, ShardKeys.physicalTable(spec.table(), k)));
                }
            } else {
                source = readAll(con, spec.table());
            }
            sourceRows = source.size();
            for (Map<String, Object> row : source) {
                String target = toLegacy
                        ? spec.table()
                        : ShardKeys.physicalTable(spec.table(), ShardKeys.shardOf(
                                row.get(spec.shardColumn()) == null ? null : String.valueOf(row.get(spec.shardColumn())),
                                shardCount));
                if (upsert(con, spec, target, row)) {
                    written++;
                } else {
                    conflicts++;
                    log.error("I-5b 全量同步唯一键冲突（拒绝改写他行）table={} target={} pk={}",
                            spec.table(), target, row.get(spec.pk()));
                }
            }
        } catch (SQLException e) {
            out.put("error", e.getMessage());
        }
        out.put("sourceRows", sourceRows);
        out.put("written", written);
        out.put("conflicts", conflicts);
        return out;
    }

    // ------------------------------------------------------------- JDBC 工具

    /** 分片侧全量行：分片态经逻辑表归并读；单库态读各物理表并集（不经 ShardingSphere）。 */
    public static List<Map<String, Object>> readShardSide(DataSource raw, DataSource routed, boolean active,
                                                          Spec spec, int shardCount) throws SQLException {
        if (active) {
            try (Connection con = routed.getConnection()) {
                return readAll(con, spec.table());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection con = raw.getConnection()) {
            for (int k = 0; k < shardCount; k++) {
                out.addAll(readAll(con, ShardKeys.physicalTable(spec.table(), k)));
            }
        }
        return out;
    }

    public static List<Map<String, Object>> readAll(DataSource ds, String table) throws SQLException {
        try (Connection con = ds.getConnection()) {
            return readAll(con, table);
        }
    }

    public static List<Map<String, Object>> readAll(Connection con, String table) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                out.add(row(rs, meta));
            }
        }
        return out;
    }

    public static Map<String, Object> readOne(Connection con, String table, String pk, Object value)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM " + table + " WHERE " + pk + "=?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs, rs.getMetaData()) : null;
            }
        }
    }

    private static Map<String, Object> row(ResultSet rs, ResultSetMetaData meta) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            out.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return out;
    }

    private static Map<String, Map<String, Object>> byPk(List<Map<String, Object>> rows, String pk) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(norm(row.get(pk)), row);
        }
        return out;
    }

    /**
     * 影子侧写入：主键存在→只 UPDATE 非主键列；不存在→INSERT。
     * 主键不存在但其它唯一键冲突→返回 false（绝不改写他行）。绝不 DELETE。
     */
    public static boolean upsert(Connection con, Spec spec, String target, Map<String, Object> row)
            throws SQLException {
        Object pkValue = row.get(spec.pk());
        if (pkValue == null) {
            return false;
        }
        if (existsByPk(con, target, spec.pk(), pkValue)) {
            updateByPk(con, spec, target, row);
            return true;
        }
        List<String> cols = new ArrayList<>(row.keySet());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(target).append('(');
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ",").append(cols.get(i));
        }
        sql.append(") VALUES(");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < cols.size(); i++) {
                ps.setObject(i + 1, row.get(cols.get(i)));
            }
            ps.executeUpdate();
            return true;
        } catch (SQLException dup) {
            if (ShardTableBootstrap.isDuplicate(dup)) {
                return false;
            }
            throw dup;
        }
    }

    private static boolean existsByPk(Connection con, String table, String pk, Object value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM " + table + " WHERE " + pk + "=?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void updateByPk(Connection con, Spec spec, String target, Map<String, Object> row)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String col : row.keySet()) {
            if (!col.equalsIgnoreCase(spec.pk())) {
                cols.add(col);
            }
        }
        if (cols.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(target).append(" SET ");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ",").append(cols.get(i)).append("=?");
        }
        sql.append(" WHERE ").append(spec.pk()).append("=?");
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int at = 1;
            for (String col : cols) {
                ps.setObject(at++, row.get(col));
            }
            ps.setObject(at, row.get(spec.pk()));
            ps.executeUpdate();
        }
    }
}