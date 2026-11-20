package com.qoder.payment.trade.web;

import com.qoder.payment.common.db.FundShardEndpointSupport;
import com.qoder.payment.common.db.FundShardPreBootstrap;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.trade.config.TradeShardConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-5b 交易域分片验证端点 {@code /actuator/ppshard}。
 *
 * <ul>
 *   <li>{@code GET}：路由态 + 物理行数 + 双写计数 + 订单状态分布</li>
 *   <li>{@code GET /shadowCompare}：原单表 vs 分片侧逐行逐列比对</li>
 *   <li>{@code GET /shardSummary}：逐片订单聚合 + 各片之和 + 逻辑表归并 + 原单表</li>
 *   <li>{@code GET /merchant/{merchantId}}：单片路由 + 该商户物理分布（merchant+outTradeNo 幂等键同片）</li>
 *   <li>{@code POST} {"action":"toggle","enabled":false} 一键回滚；{"action":"resync"}；{"action":"idgen"}（前缀 O）</li>
 * </ul>
 */
@Component
@Endpoint(id = "ppshard")
public class TradeShardEndpoint {

    private static final String TABLE = "trade_order";
    // I-5b：SUM 顶层投影才会被 ShardingSphere 跨片归并；NULL 由 num()/scalarSingle 归零。
    private static final String[] EXPR = {"COUNT(*)", "SUM(amount)",
            "SUM(CASE WHEN status='PAID' THEN 1 ELSE 0 END)",
            "SUM(CASE WHEN status='CREATED' THEN 1 ELSE 0 END)",
            "SUM(refunded_amount)"};

    private final ObjectProvider<FundShardEndpointSupport> support;
    private final ObjectProvider<ShardProbeSupport> probe;
    private final ObjectProvider<FundShardPreBootstrap> bootstrap;

    public TradeShardEndpoint(ObjectProvider<FundShardEndpointSupport> support,
                              ObjectProvider<ShardProbeSupport> probe,
                              ObjectProvider<FundShardPreBootstrap> bootstrap) {
        this.support = support;
        this.probe = probe;
        this.bootstrap = bootstrap;
    }

    @ReadOperation
    public Map<String, Object> status() {
        FundShardEndpointSupport s = support.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("domain", TradeShardConfig.DOMAIN);
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
            return out;
        }
        FundShardPreBootstrap b = bootstrap.getIfAvailable();
        if (b != null) {
            s.bootstrapStats(b.stats());
        }
        out.putAll(s.status());
        return out;
    }

    @ReadOperation
    public Object view(@Selector String view) {
        FundShardEndpointSupport s = support.getIfAvailable();
        switch (view) {
            case "shadowCompare":
                return s == null ? Map.of("shardingConfigured", false, "diffTotal", 0) : s.shadowCompare();
            case "shardSummary":
                return shardSummary();
            default:
                return Map.of("error", "未知视图: " + view, "available", List.of("shadowCompare", "shardSummary"));
        }
    }

    @ReadOperation
    public Object detail(@Selector String view, @Selector String arg) {
        if (!"merchant".equals(view)) {
            return Map.of("error", "未知视图: " + view, "available", List.of("merchant"));
        }
        ShardProbeSupport p = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        String merchantId = "-".equals(arg) ? "" : arg;
        out.put("merchantId", merchantId);
        if (p == null) {
            out.put("shardingConfigured", false);
            return out;
        }
        out.put("expectedShard", ShardKeys.shardOf(merchantId, p.runtime().shardCount()));
        out.put("routedCount", p.rowsRouted("SELECT COUNT(*) AS c FROM " + TABLE + " WHERE merchant_id=?", merchantId));
        Map<String, Object> distribution = new LinkedHashMap<>();
        for (int k = 0; k < p.runtime().shardCount(); k++) {
            distribution.put(ShardKeys.physicalTable(TABLE, k),
                    p.scalarSingle("SELECT COUNT(*) FROM " + ShardKeys.physicalTable(TABLE, k) + " WHERE merchant_id=?",
                            merchantId));
        }
        out.put("physicalDistribution", distribution);
        return out;
    }

    @WriteOperation
    public Map<String, Object> write(@Nullable String action, @Nullable Boolean enabled,
                                     @Nullable Integer count, @Nullable Integer threads,
                                     @Nullable String direction) {
        FundShardEndpointSupport s = support.getIfAvailable();
        String op = action == null ? "toggle" : action;
        if (s == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("message", "未装配分片 Bean（payment.sharding.enabled=false），始终单库 + next_value_for");
            return out;
        }
        switch (op) {
            case "idgen":
                return s.idgen("O", count, threads);
            case "compare":
                return s.shadowCompare();
            case "resync":
                return s.resync(direction);
            default:
                return s.toggle(enabled);
        }
    }

    private Map<String, Object> shardSummary() {
        ShardProbeSupport p = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardingConfigured", p != null);
        if (p == null) {
            return out;
        }
        int count = p.runtime().shardCount();
        List<Map<String, Object>> shards = new ArrayList<>();
        long[] sum = new long[EXPR.length];
        for (int k = 0; k < count; k++) {
            Map<String, Object> row = physical(p, ShardKeys.physicalTable(TABLE, k));
            shards.add(row);
            for (int i = 0; i < sum.length; i++) {
                sum[i] += num(row, "v" + i);
            }
        }
        out.put("shards", shards);
        out.put("shardSum", sums(sum));
        out.put("legacy", physical(p, TABLE));
        out.put("routed", routed(p));
        return out;
    }

    private static Map<String, Object> physical(ShardProbeSupport p, String table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        for (int i = 0; i < EXPR.length; i++) {
            row.put("v" + i, p.scalarSingle("SELECT " + EXPR[i] + " FROM " + table));
        }
        return row;
    }

    private static Map<String, Object> routed(ShardProbeSupport p) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < EXPR.length; i++) {
            sql.append(i == 0 ? "" : ",").append(EXPR[i]).append(" AS v").append(i);
        }
        sql.append(" FROM ").append(TABLE);
        List<Map<String, Object>> rows = p.rowsRouted(sql.toString());
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", TABLE);
        for (int i = 0; i < EXPR.length; i++) {
            out.put("v" + i, num(first, "v" + i));
        }
        return out;
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static List<Long> sums(long[] values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) {
            out.add(v);
        }
        return out;
    }
}