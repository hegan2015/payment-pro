package com.qoder.payment.recon.web;

import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.recon.service.ReconService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-5a 分片验证端点 {@code /actuator/ppshard}（recon 域）。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppshard}：分片运行态 + recon_report 物理行数 + 报告总数</li>
 *   <li>{@code GET /actuator/ppshard/page/{offset}:{size}}：逻辑表跨片分页（归并引擎）</li>
 *   <li>{@code GET /actuator/ppshard/physical/-}：各物理表与原单表全量 (id,checked_at,pass)，供不重不漏对照</li>
 *   <li>{@code GET /actuator/ppshard/route/{reportId}}：PREVIEW 真实路由</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"toggle","enabled":false}：一键回滚单库</li>
 *   <li>{@code POST /actuator/ppshard} {"action":"idgen","count":50000,"threads":8}：全局发号压测</li>
 * </ul>
 */
@Component
@Endpoint(id = "ppshard")
@RequiredArgsConstructor
public class ReconShardProbeEndpoint {

    private static final String TABLE = "recon_report";
    private static final String COLS = "id,checked_at,pass";
    private static final String ORDER = " ORDER BY checked_at DESC, id DESC";

    private final ReconService reconService;
    private final ObjectProvider<ShardProbeSupport> probe;

    @ReadOperation
    public Map<String, Object> status() {
        ShardProbeSupport support = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (support == null) {
            out.put("domain", "payment-recon");
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
        } else {
            out.putAll(support.status());
        }
        out.put("reportCount", reconService.reportCount());
        return out;
    }

    @ReadOperation
    public Object view(@Selector String view) {
        if ("count".equals(view)) {
            return Map.of("reportCount", reconService.reportCount());
        }
        return Map.of("error", "未知视图: " + view, "available", List.of("count"));
    }

    @ReadOperation
    public Object detail(@Selector String view, @Selector String arg) {
        ShardProbeSupport support = probe.getIfAvailable();
        switch (view) {
            case "page": {
                int[] range = range(arg);
                return reconService.reportsPage(range[0], range[1]);
            }
            case "physical": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("shardingConfigured", support != null);
                if (support == null) {
                    return out;
                }
                Map<String, Object> tables = new LinkedHashMap<>();
                tables.put(TABLE, support.rowsSingle("SELECT " + COLS + " FROM " + TABLE + ORDER));
                for (int k = 0; k < support.runtime().shardCount(); k++) {
                    String physical = ShardKeys.physicalTable(TABLE, k);
                    tables.put(physical, support.rowsSingle("SELECT " + COLS + " FROM " + physical + ORDER));
                }
                out.put("tables", tables);
                return out;
            }
            case "route": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("reportId", arg);
                if (support == null) {
                    out.put("shardingConfigured", false);
                    return out;
                }
                out.put("previewWriteRouteOnly", support.preview(
                        "SELECT * FROM " + TABLE + " WHERE id='" + arg.replace("'", "") + "'", true));
                Map<String, Object> distribution = new LinkedHashMap<>();
                for (int k = 0; k < support.runtime().shardCount(); k++) {
                    String physical = ShardKeys.physicalTable(TABLE, k);
                    distribution.put(physical, support.scalarSingle("SELECT COUNT(*) FROM " + physical + " WHERE id=?", arg));
                }
                out.put("physicalDistribution", distribution);
                out.put("expectedShard", ShardKeys.shardOf(arg, support.runtime().shardCount()));
                return out;
            }
            default:
                return Map.of("error", "未知视图: " + view, "available", List.of("page", "physical", "route"));
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
            return support.idStress("RC", count == null ? 50000 : count, threads == null ? 8 : threads);
        }
        boolean target = !Boolean.FALSE.equals(enabled);
        boolean before = support.runtime().setActive(target);
        out.put("before", before);
        out.put("shardingActive", support.runtime().isActive());
        out.put("idMode", support.runtime().isActive() ? "SNOWFLAKE" : "SEQUENCE");
        return out;
    }

    private static int[] range(String arg) {
        String[] parts = arg.split(":");
        int offset = Integer.parseInt(parts[0]);
        int size = parts.length > 1 ? Integer.parseInt(parts[1]) : 20;
        return new int[]{offset, size};
    }
}