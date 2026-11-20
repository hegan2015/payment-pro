package com.qoder.payment.payment.config;

import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardPreBootstrap;
import com.qoder.payment.common.db.ShardRouteState;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-5b 支付域分片前置初始化（在 ShardingSphere DataSource 创建<b>之前</b>执行，只依赖原单库连接）。
 *
 * <ol>
 *   <li>建 {@code shard_route_state}（单库不分片）并<b>恢复持久化路由</b>：
 *       切流后重启不会退回默认态，启动同步方向永远正确（资金安全前置）；</li>
 *   <li>建 {@code payment_request_index} / {@code refund_request_index}（单库不分片，跨片全局幂等），
 *       并从既有支付单/退款单<b>只 INSERT IGNORE 回填</b>索引（原行零改动）；</li>
 *   <li>按权威侧做<b>全行同步</b>（可变行必须逐列一致，只 UPDATE 影子副本 / INSERT 缺行，绝不 DELETE），
 *       随后逐行逐列比对并把差异数记入运行态，供端点与 verify-sharding-fund.py 断言。</li>
 * </ol>
 *
 * <p>协调器库表 {@code dtx_transaction / dtx_branch / outbox} 与 {@code idempotent_record / sequences /
 * shedlock / amount_review} <b>一律不分片</b>（SINGLE 规则钉在原库），终态与 outbox 原子性不破坏。
 */
public class PaymentShardBootstrap implements ShardPreBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PaymentShardBootstrap.class);

    private final ShardRouteState routeState;
    private final List<ShardRowMirror.Spec> specs;

    public PaymentShardBootstrap(ShardRouteState routeState, List<ShardRowMirror.Spec> specs) {
        this.routeState = routeState;
        this.specs = specs;
    }

    @Override
    public void preBootstrap(DataSource raw, ShardRuntime runtime) {
        Map<String, Object> stats = new LinkedHashMap<>();
        routeState.ensureTable();
        Boolean persisted = routeState.read();
        if (persisted != null) {
            runtime.setActive(persisted);
        } else {
            routeState.write(runtime.isActive());
        }
        stats.put("routeActive", runtime.isActive());
        stats.put("routeRestoredFrom", persisted == null ? "initial-active" : "shard_route_state");
        ensureIndexTables(raw);
        stats.put("indexBackfill", backfill(raw, runtime.shardCount()));
        // 权威侧 = 当前路由侧：分片态权威侧是分片表（同步回原单表）；单库态权威侧是原单表（同步进分片表）。
        // 方向绝不能反，否则会用较旧的影子副本覆盖较新的业务行（资金安全红线）。
        boolean toLegacy = runtime.isActive();
        Map<String, Object> sync = new LinkedHashMap<>();
        Map<String, Object> compare = new LinkedHashMap<>();
        long diff = 0;
        for (ShardRowMirror.Spec spec : specs) {
            sync.put(spec.table(), ShardRowMirror.syncRaw(raw, spec, runtime.shardCount(), toLegacy));
            Map<String, Object> one = ShardRowMirror.compareRaw(raw, raw, false, spec, runtime.shardCount());
            compare.put(spec.table(), one);
            diff += ((Number) one.get("diffTotal")).longValue();
        }
        stats.put("syncDirection", toLegacy ? "TO_LEGACY" : "TO_SHARDS");
        stats.put("sync", sync);
        stats.put("compare", compare);
        stats.put("diffTotal", diff);
        if (diff != 0) {
            log.error("I-5b 支付域启动同步后仍有 {} 行差异（禁止切流，需人工核查；原行零删除）: {}", diff, compare);
        }
        log.warn("I-5b 支付域分片前置初始化完成 route={} 索引回填={} 同步方向={} 差异={}",
                runtime.isActive() ? "SHARDED" : "SINGLE", stats.get("indexBackfill"),
                stats.get("syncDirection"), diff);
        PaymentShardStats.HOLDER.set(stats);
    }

    /** 跨片全局幂等索引表（单库不分片）。只 CREATE IF NOT EXISTS。 */
    private static void ensureIndexTables(DataSource raw) {
        try (Connection con = raw.getConnection(); Statement st = con.createStatement()) {
            con.setAutoCommit(true);
            st.execute("CREATE TABLE IF NOT EXISTS payment_request_index ("
                    + "request_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "payment_id VARCHAR(32) NOT NULL,"
                    + "shard INT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "INDEX idx_pri_payment (payment_id))");
            st.execute("CREATE TABLE IF NOT EXISTS refund_request_index ("
                    + "request_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "refund_id VARCHAR(32) NOT NULL,"
                    + "payment_id VARCHAR(32) NOT NULL,"
                    + "shard INT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "INDEX idx_rri_refund (refund_id))");
        } catch (SQLException e) {
            throw new IllegalStateException("I-5b 幂等索引表建表失败（fail-fast，绝不无索引分片）: " + e.getMessage(), e);
        }
    }

    /**
     * 从既有支付单/退款单回填索引（只 INSERT，主键冲突即跳过）。
     * 同时覆盖原单表与各分片表，保证任一侧曾写入的行都有全局幂等占位。
     */
    public static Map<String, Object> backfill(DataSource raw, int shardCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection con = raw.getConnection()) {
            con.setAutoCommit(true);
            out.put("paymentIndexInserted", backfillPayment(con, shardCount));
            out.put("refundIndexInserted", backfillRefund(con, shardCount));
            out.put("paymentIndexRows", count(con, "payment_request_index"));
            out.put("refundIndexRows", count(con, "refund_request_index"));
        } catch (SQLException e) {
            throw new IllegalStateException("I-5b 幂等索引回填失败: " + e.getMessage(), e);
        }
        return out;
    }

    private static int backfillPayment(Connection con, int shardCount) throws SQLException {
        List<String> tables = new ArrayList<>();
        tables.add("payment");
        for (int k = 0; k < shardCount; k++) {
            tables.add(ShardKeys.physicalTable("payment", k));
        }
        int inserted = 0;
        for (String table : tables) {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT IGNORE INTO payment_request_index(request_id,payment_id,shard,created_at) VALUES(?,?,?,?)");
                 Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,request_id,created_at FROM " + table)) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    ps.setString(1, rs.getString(2));
                    ps.setString(2, id);
                    ps.setInt(3, ShardKeys.shardOf(id, shardCount));
                    ps.setLong(4, rs.getLong(3));
                    inserted += ps.executeUpdate();
                }
            }
        }
        return inserted;
    }

    private static int backfillRefund(Connection con, int shardCount) throws SQLException {
        List<String> tables = new ArrayList<>();
        tables.add("refund_record");
        for (int k = 0; k < shardCount; k++) {
            tables.add(ShardKeys.physicalTable("refund_record", k));
        }
        int inserted = 0;
        for (String table : tables) {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT IGNORE INTO refund_request_index(request_id,refund_id,payment_id,shard,created_at) VALUES(?,?,?,?,?)");
                 Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,request_id,payment_id,created_at FROM " + table)) {
                while (rs.next()) {
                    ps.setString(1, rs.getString(2));
                    ps.setString(2, rs.getString(1));
                    ps.setString(3, rs.getString(3));
                    ps.setInt(4, ShardKeys.shardOf(rs.getString(3), shardCount));
                    ps.setLong(5, rs.getLong(4));
                    inserted += ps.executeUpdate();
                }
            }
        }
        return inserted;
    }

    private static long count(Connection con, String table) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 启动统计传递到端点（避免 Bean 依赖成环：前置初始化早于路由 DataSource 创建）。 */
    public static final class PaymentShardStats {
        public static final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> HOLDER =
                new java.util.concurrent.atomic.AtomicReference<>(new LinkedHashMap<>());

        private PaymentShardStats() {
        }
    }
}