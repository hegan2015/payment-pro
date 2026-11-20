package com.qoder.payment.payment.web;

import com.qoder.payment.common.db.FundShardEndpointSupport;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.payment.config.PaymentShardBootstrap;
import com.qoder.payment.payment.service.PaymentRequestIndex;
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
 * I-5b 支付域分片验证端点 {@code /actuator/ppshard}。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppshard}：路由态 + 物理行数 + 双写计数 + 幂等索引行数 + 启动同步差异</li>
 *   <li>{@code GET /actuator/ppshard/shadowCompare}：原单表 vs 分片侧逐行逐列比对（差异数）</li>
 *   <li>{@code GET /actuator/ppshard/shardSummary}：逐物理表聚合 + 各片之和 + 逻辑表归并 + 原单表</li>
 *   <li>{@code GET /actuator/ppshard/index/{requestId}}：跨片幂等索引占位查片</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"toggle","enabled":false}：一键回滚（差异非 0 即拒绝）</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"resync","direction":"TO_LEGACY"}：全量修复同步</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"idgen","count":50000,"threads":8}：全局发号压测</li>
 * </ul>
 *
 * <p>只读探针不改业务数据；toggle/resync 只新增或同步影子副本，<b>绝不删除任何行</b>。
 */
@Component
@Endpoint(id = "ppshard")
public class PaymentShardEndpoint {

    private final ObjectProvider<FundShardEndpointSupport> support;
    private final ObjectProvider<ShardProbeSupport> probe;
    private final ObjectProvider<PaymentRequestIndex> index;

    public PaymentShardEndpoint(ObjectProvider<FundShardEndpointSupport> support,
                                ObjectProvider<ShardProbeSupport> probe,
                                ObjectProvider<PaymentRequestIndex> index) {
        this.support = support;
        this.probe = probe;
        this.index = index;
    }

    @ReadOperation
    public Map<String, Object> status() {
        FundShardEndpointSupport s = support.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("domain", "payment-payment");
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
            return out;
        }
        s.bootstrapStats(PaymentShardBootstrap.PaymentShardStats.HOLDER.get());
        out.putAll(s.status());
        PaymentRequestIndex idx = index.getIfAvailable();
        if (idx != null) {
            out.put("paymentRequestIndexRows", idx.paymentIndexRows());
            out.put("refundRequestIndexRows", idx.refundIndexRows());
        }
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
        Map<String, Object> out = new LinkedHashMap<>();
        ShardProbeSupport p = probe.getIfAvailable();
        if ("payment".equals(view)) {
            out.put("paymentId", arg);
            if (p == null) {
                out.put("shardingConfigured", false);
                return out;
            }
            out.put("expectedShard", ShardKeys.shardOf(arg, p.runtime().shardCount()));
            Map<String, Object> distribution = new LinkedHashMap<>();
            for (int k = 0; k < p.runtime().shardCount(); k++) {
                distribution.put(ShardKeys.physicalTable("payment", k),
                        p.scalarSingle("SELECT COUNT(*) FROM " + ShardKeys.physicalTable("payment", k) + " WHERE id=?", arg));
            }
            out.put("physicalDistribution", distribution);
            out.put("legacyRows", p.scalarSingle("SELECT COUNT(*) FROM payment WHERE id=?", arg));
            out.put("routedRows", p.rowsRouted("SELECT id,request_id,status,amount,fee FROM payment WHERE id=?", arg));
            return out;
        }
        if (!"index".equals(view)) {
            return Map.of("error", "未知视图: " + view, "available", List.of("index", "payment"));
        }
        out.put("requestId", arg);
        PaymentRequestIndex idx = index.getIfAvailable();
        if (idx == null || p == null) {
            out.put("shardingConfigured", false);
            return out;
        }
        String paymentId = idx.lookupPaymentId(arg);
        out.put("indexPaymentId", paymentId);
        out.put("expectedShard", paymentId == null ? null : ShardKeys.shardOf(paymentId, p.runtime().shardCount()));
        out.put("routedPayment", p.rowsRouted("SELECT id,request_id,status,amount FROM payment WHERE request_id=?", arg));
        out.put("legacyPayment", p.rowsSingle("SELECT id,request_id,status,amount FROM payment WHERE request_id=?", arg));
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
                return s.idgen("P", count, threads);
            case "compare":
                return s.shadowCompare();
            case "resync":
                return s.resync(direction);
            default:
                return s.toggle(enabled);
        }
    }

    /** 跨片聚合对照：逐物理表（原单库直连）+ 各片之和 + 逻辑表归并 + 原单表。 */
    private Map<String, Object> shardSummary() {
        ShardProbeSupport p = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardingConfigured", p != null);
        if (p == null) {
            return out;
        }
        int count = p.runtime().shardCount();
        // I-5b：SUM 顶层投影才会被 ShardingSphere 跨片归并；NULL 由 num()/scalarSingle 归零。
        String[] payExpr = {"COUNT(*)", "SUM(amount)", "SUM(fee)", "SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END)"};
        String[] refExpr = {"COUNT(*)", "SUM(amount)", "SUM(fee_refund)"};
        List<Map<String, Object>> paymentShards = new ArrayList<>();
        List<Map<String, Object>> refundShards = new ArrayList<>();
        long[] paySum = new long[payExpr.length];
        long[] refSum = new long[refExpr.length];
        for (int k = 0; k < count; k++) {
            Map<String, Object> pay = aggregates(p, ShardKeys.physicalTable("payment", k), payExpr);
            paymentShards.add(pay);
            for (int i = 0; i < paySum.length; i++) {
                paySum[i] += num(pay, "v" + i);
            }
            Map<String, Object> ref = aggregates(p, ShardKeys.physicalTable("refund_record", k), refExpr);
            refundShards.add(ref);
            for (int i = 0; i < refSum.length; i++) {
                refSum[i] += num(ref, "v" + i);
            }
        }
        out.put("paymentShards", paymentShards);
        out.put("refundShards", refundShards);
        out.put("paymentShardSum", sums(paySum));
        out.put("refundShardSum", sums(refSum));
        out.put("paymentLegacy", aggregates(p, "payment", payExpr));
        out.put("refundLegacy", aggregates(p, "refund_record", refExpr));
        out.put("paymentRouted", routed(p, "payment", payExpr));
        out.put("refundRouted", routed(p, "refund_record", refExpr));
        return out;
    }

    private static Map<String, Object> aggregates(ShardProbeSupport p, String table, String... exprs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        for (int i = 0; i < exprs.length; i++) {
            row.put("v" + i, p.scalarSingle("SELECT " + exprs[i] + " FROM " + table));
        }
        return row;
    }

    private static Map<String, Object> routed(ShardProbeSupport p, String table, String... exprs) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < exprs.length; i++) {
            sql.append(i == 0 ? "" : ",").append(exprs[i]).append(" AS v").append(i);
        }
        sql.append(" FROM ").append(table);
        List<Map<String, Object>> rows = p.rowsRouted(sql.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        for (int i = 0; i < exprs.length; i++) {
            Object v = first.get("v" + i);
            out.put("v" + i, v == null ? 0L : ((Number) v).longValue());
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