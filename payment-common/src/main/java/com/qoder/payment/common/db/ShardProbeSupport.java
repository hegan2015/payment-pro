package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * I-5a 分片验证支撑（只读探针 + 全局发号压测），供各域 {@code /actuator/ppshard} 端点复用。
 *
 * <p>物理表统计一律走<b>原单库 DataSource</b>（可见 settle_record 与 settle_record_0..3 真实行数），
 * 逻辑表查询走 {@code @Primary} 路由 DataSource（分片期即 ShardingSphere），两者对照即可证明
 * 「单片路由 / 跨片 SUM 归并 / 跨片分页不重不漏」。
 */
public class ShardProbeSupport {

    private static final Logger log = LoggerFactory.getLogger(ShardProbeSupport.class);

    private final DataSource raw;
    private final DataSource routed;
    private final ShardRuntime runtime;
    private final IdGenerator idGenerator;

    public ShardProbeSupport(DataSource raw, DataSource routed, ShardRuntime runtime, IdGenerator idGenerator) {
        this.raw = raw;
        this.routed = routed;
        this.runtime = runtime;
        this.idGenerator = idGenerator;
    }

    public ShardRuntime runtime() {
        return runtime;
    }

    public IdGenerator idGenerator() {
        return idGenerator;
    }

    /** 基础状态（端点各自补充域内指标）。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", runtime.appName());
        out.put("shardingConfigured", true);
        out.put("shardingActive", runtime.isActive());
        out.put("shardCount", runtime.shardCount());
        out.put("shardTables", runtime.tables());
        out.put("readwriteSplitting", runtime.readwriteSplitting());
        out.put("mirrorLegacy", runtime.mirrorLegacy());
        out.put("mirrorRows", runtime.mirrorRows());
        out.put("mirrorFailures", runtime.mirrorFailures());
        out.put("writeRouteHintReads", runtime.hintReads());
        out.put("idMode", runtime.isActive() ? "SNOWFLAKE" : "SEQUENCE");
        out.put("idGenerated", idGenerator.generatedCount());
        out.put("bootstrap", runtime.bootstrapStats());
        out.put("physicalRows", physicalRows());
        return out;
    }

    /** 原单表 + 各分片表真实行数（原单库视角）。 */
    public Map<String, Long> physicalRows() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String logic : runtime.tables().keySet()) {
            out.put(logic, scalarSingle("SELECT COUNT(*) FROM " + logic));
            for (int k = 0; k < runtime.shardCount(); k++) {
                String physical = ShardKeys.physicalTable(logic, k);
                out.put(physical, scalarSingle("SELECT COUNT(*) FROM " + physical));
            }
        }
        return out;
    }

    public long scalarSingle(String sql, Object... args) {
        try (Connection con = raw.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("原单库标量查询失败: " + sql + " / " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> rowsSingle(String sql, Object... args) {
        return query(raw, sql, args);
    }

    /** 逻辑表查询（分片期经 ShardingSphere 归并引擎）。 */
    public List<Map<String, Object>> rowsRouted(String sql, Object... args) {
        return query(routed, sql, args);
    }

    private static List<Map<String, Object>> query(DataSource ds, String sql, Object... args) {
        try (Connection con = ds.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql + " / " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    /**
     * ShardingSphere {@code PREVIEW}：返回一条 SQL 的真实路由（dataSourceName + actualSQL）。
     * 用于断言 {@code records(merchantId)} 只落单片、读己之写 hint 命中主库。
     * 不支持时返回 {@code supported=false}（端点降级为物理分布对照断言）。
     */
    public Map<String, Object> preview(String sql, boolean writeRouteOnly) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", sql);
        out.put("writeRouteOnly", writeRouteOnly);
        List<Map<String, Object>> routes = new ArrayList<>();
        try {
            List<Map<String, Object>> raw = writeRouteOnly
                    ? runtime.readYourWrite(() -> query(routed, "PREVIEW " + sql))
                    : query(routed, "PREVIEW " + sql);
            routes.addAll(raw);
            out.put("supported", true);
        } catch (Exception e) {
            out.put("supported", false);
            out.put("reason", e.getMessage());
        }
        out.put("routes", routes);
        return out;
    }

    /** 全局发号压测：多线程并发取号，返回去重统计（verify-sharding.py 断言 10 万无重号）。 */
    public Map<String, Object> idStress(String prefix, int count, int threads) {
        int total = Math.max(1, Math.min(count, 500_000));
        int pool = Math.max(1, Math.min(threads, 32));
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        List<String> sample = new ArrayList<>();
        long start = System.currentTimeMillis();
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        CountDownLatch done = new CountDownLatch(pool);
        int per = total / pool;
        int remainder = total % pool;
        for (int t = 0; t < pool; t++) {
            int n = per + (t < remainder ? 1 : 0);
            exec.submit(() -> {
                try {
                    for (int i = 0; i < n; i++) {
                        long id = idGenerator.nextLong();
                        seen.add(id);
                        if (sample.size() < 3) {
                            synchronized (sample) {
                                if (sample.size() < 3) {
                                    sample.add((prefix == null ? "" : prefix) + id);
                                }
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("全局发号压测超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("全局发号压测被中断", e);
        } finally {
            exec.shutdownNow();
        }
        Set<Integer> workers = ConcurrentHashMap.newKeySet();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long id : seen) {
            workers.add(IdGenerator.workerIdOf(id));
            min = Math.min(min, id);
            max = Math.max(max, id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", total);
        out.put("threads", pool);
        out.put("generated", total);
        out.put("distinct", seen.size());
        out.put("duplicates", total - seen.size());
        out.put("workerId", idGenerator.workerId());
        out.put("workerIdSource", idGenerator.workerIdSource());
        out.put("workerIdsInIds", new ArrayList<>(workers));
        out.put("minId", seen.isEmpty() ? 0 : min);
        out.put("maxId", seen.isEmpty() ? 0 : max);
        out.put("sample", sample);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        log.info("I-5a 全局发号压测 count={} distinct={} workerId={} 耗时={}ms",
                total, seen.size(), idGenerator.workerId(), out.get("elapsedMs"));
        return out;
    }

    /** 只读：原单库上按物理表执行标量聚合（跨片 SUM 对照）。 */
    public long scalarPhysical(String logicTable, int shardIndex, String expression, String where) {
        String table = ShardKeys.physicalTable(logicTable, shardIndex);
        return scalarSingle("SELECT " + expression + " FROM " + table + (where == null ? "" : " WHERE " + where));
    }

    /** 关闭底层连接池（容器销毁时）。 */
    public void closePools() {
        closeQuietly(raw);
    }

    private static void closeQuietly(DataSource ds) {
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("I-5a 关闭连接池失败: {}", e.toString());
            }
        }
    }
}