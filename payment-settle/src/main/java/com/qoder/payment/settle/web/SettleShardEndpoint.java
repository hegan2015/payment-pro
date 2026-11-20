package com.qoder.payment.settle.web;

import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.facade.settle.SettleSummaryDTO;
import com.qoder.payment.settle.service.SettleService;
import lombok.RequiredArgsConstructor;
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
 * I-5a 分片验证端点 {@code /actuator/ppshard}（settle 域）。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppshard}：分片运行态 + 物理行数 + summary（未装配分片时报 shardingConfigured=false）</li>
 *   <li>{@code GET /actuator/ppshard/shardSummary}：逐物理表聚合 + 各片之和 + 逻辑表归并结果（跨片 SUM 对照）</li>
 *   <li>{@code GET /actuator/ppshard/records/{merchantId}}：单片路由查询（"-" 表示全量）</li>
 *   <li>{@code GET /actuator/ppshard/route/{merchantId}}：PREVIEW 真实路由 + 该商户物理分布</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"toggle","enabled":false}：一键回滚单库</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"idgen","count":50000,"threads":8}：全局发号压测</li>
 * </ul>
 *
 * <p>只读探针不改业务数据；toggle 只切路由，<b>不删任何行</b>。
 */
@Component
@Endpoint(id = "ppshard")
@RequiredArgsConstructor
public class SettleShardEndpoint {

    private static final String TABLE = "settle_record";
    private static final String GROSS_PAY = "COALESCE(SUM(gross),0)";
    private static final String FEE_NET = "COALESCE(SUM(CASE WHEN type='PAY' THEN fee ELSE -fee END),0)";
    private static final String NET_NET = "COALESCE(SUM(CASE WHEN type='PAY' THEN net ELSE -net END),0)";

    private final SettleService settleService;
    private final ObjectProvider<ShardProbeSupport> probe;

    @ReadOperation
    public Map<String, Object> status() {
        ShardProbeSupport support = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (support == null) {
            out.put("domain", "payment-settle");
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
        } else {
            out.putAll(support.status());
        }
        out.put("summary", summary());
        return out;
    }

    @ReadOperation
    public Object view(@Selector String view) {
        switch (view) {
            case "summary":
                return summary();
            case "shardSummary":
                return shardSummary();
            default:
                return Map.of("error", "未知视图: " + view, "available", List.of("summary", "shardSummary"));
        }
    }

    @ReadOperation
    public Object detail(@Selector String view, @Selector String arg) {
        String merchantId = "-".equals(arg) ? null : arg;
        switch (view) {
            case "records":
                return settleService.records(merchantId);
            case "route":
                return route(merchantId);
            default:
                return Map.of("error", "未知视图: " + view, "available", List.of("records", "route"));
        }
    }

    @WriteOperation
    public Map<String, Object> write(@Nullable String action, @Nullable Boolean enabled,
                                     @Nullable Integer count, @Nullable Integer threads) {
        String op = action == null ? "toggle" : action;
        ShardProbeSupport support = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (support == null) {
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("message", "未装配分片 Bean（payment.sharding.enabled=false），始终单库 + next_value_for");
            return out;
        }
        if ("idgen".equals(op)) {
            return support.idStress("S", count == null ? 50000 : count, threads == null ? 8 : threads);
        }
        boolean target = !Boolean.FALSE.equals(enabled);
        boolean before = support.runtime().setActive(target);
        out.put("before", before);
        out.put("shardingActive", support.runtime().isActive());
        out.put("idMode", support.runtime().isActive() ? "SNOWFLAKE" : "SEQUENCE");
        return out;
    }

    private Map<String, Object> summary() {
        SettleSummaryDTO dto = settleService.summary();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payCount", dto.getPayCount());
        out.put("totalGross", dto.getTotalGross());
        out.put("totalFee", dto.getTotalFee());
        out.put("totalNet", dto.getTotalNet());
        out.put("totalRefund", dto.getTotalRefund());
        return out;
    }

    /** 跨片聚合对照：逐物理表聚合（原单库直连）+ 各片之和 + 逻辑表归并结果。 */
    private Map<String, Object> shardSummary() {
        ShardProbeSupport support = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardingConfigured", support != null);
        out.put("routed", summary());
        if (support == null) {
            return out;
        }
        int count = support.runtime().shardCount();
        List<Map<String, Object>> shards = new ArrayList<>();
        long[] sum = new long[5];
        for (int k = 0; k < count; k++) {
            Map<String, Object> row = aggregates(support, ShardKeys.physicalTable(TABLE, k));
            shards.add(row);
            sum[0] += ((Number) row.get("payCount")).longValue();
            sum[1] += ((Number) row.get("totalGross")).longValue();
            sum[2] += ((Number) row.get("totalFee")).longValue();
            sum[3] += ((Number) row.get("totalNet")).longValue();
            sum[4] += ((Number) row.get("totalRefund")).longValue();
        }
        Map<String, Object> shardSum = new LinkedHashMap<>();
        shardSum.put("payCount", sum[0]);
        shardSum.put("totalGross", sum[1]);
        shardSum.put("totalFee", sum[2]);
        shardSum.put("totalNet", sum[3]);
        shardSum.put("totalRefund", sum[4]);
        out.put("shards", shards);
        out.put("shardSum", shardSum);
        out.put("legacySingleTable", aggregates(support, TABLE));
        return out;
    }

    private Map<String, Object> aggregates(ShardProbeSupport support, String table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        row.put("payCount", support.scalarSingle("SELECT COUNT(*) FROM " + table + " WHERE type='PAY'"));
        row.put("totalGross", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + table + " WHERE type='PAY'"));
        row.put("totalFee", support.scalarSingle("SELECT " + FEE_NET + " FROM " + table));
        row.put("totalNet", support.scalarSingle("SELECT " + NET_NET + " FROM " + table));
        row.put("totalRefund", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + table + " WHERE type='REFUND'"));
        return row;
    }

    private Map<String, Object> route(String merchantId) {
        ShardProbeSupport support = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        String id = merchantId == null ? "" : merchantId;
        out.put("merchantId", id);
        out.put("routedCount", settleService.records(id).size());
        if (support == null) {
            out.put("shardingConfigured", false);
            return out;
        }
        out.put("previewWriteRouteOnly", support.preview(
                "SELECT * FROM " + TABLE + " WHERE merchant_id='" + id.replace("'", "") + "'", true));
        Map<String, Object> distribution = new LinkedHashMap<>();
        for (int k = 0; k < support.runtime().shardCount(); k++) {
            String physical = ShardKeys.physicalTable(TABLE, k);
            distribution.put(physical, support.scalarSingle("SELECT COUNT(*) FROM " + physical + " WHERE merchant_id=?", id));
        }
        out.put("physicalDistribution", distribution);
        out.put("expectedShard", ShardKeys.shardOf(id, support.runtime().shardCount()));
        return out;
    }
}