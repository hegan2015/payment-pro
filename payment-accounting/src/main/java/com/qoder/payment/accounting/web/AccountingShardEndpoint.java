package com.qoder.payment.accounting.web;

import com.qoder.payment.accounting.config.AccountingShardConfig;
import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.common.db.FundShardEndpointSupport;
import com.qoder.payment.common.db.FundShardPreBootstrap;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.facade.accounting.TrialDTO;
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
 * I-5b 账务域分片验证端点 {@code /actuator/ppshard}。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppshard}：路由态 + 物理行数 + 双写计数 + 试算平衡（跨片归并）</li>
 *   <li>{@code GET /actuator/ppshard/shadowCompare}：原单表 vs 分片侧逐行逐列比对</li>
 *   <li>{@code GET /actuator/ppshard/shardSummary}：逐片余额/借贷聚合 + 各片之和 + 逻辑表归并 + 原单表</li>
 *   <li>{@code GET /actuator/ppshard/trial}：试算平衡（ΣD=ΣC、Σbalance=0）</li>
 *   <li>{@code POST} {"action":"toggle","enabled":false} 一键回滚；{"action":"resync"} 全量修复；
 *       {"action":"idgen"} 全局发号压测（前缀 E）</li>
 * </ul>
 */
@Component
@Endpoint(id = "ppshard")
public class AccountingShardEndpoint {

    private static final String BALANCE = "account_balance";
    private static final String ENTRY = "ledger_entry";
    // I-5b：SUM 必须是顶层投影，ShardingSphere 才会跨片归并（COALESCE 包裹会被当普通投影 → 每片一行）。
    // NULL（空表）由 num()/scalarSingle 统一归零，物理表与逻辑表口径一致。
    private static final String[] BALANCE_EXPR = {"COUNT(*)", "SUM(balance)", "SUM(frozen)"};
    private static final String[] ENTRY_EXPR = {"COUNT(*)",
            "SUM(CASE WHEN dir='D' THEN amount ELSE 0 END)",
            "SUM(CASE WHEN dir='C' THEN amount ELSE 0 END)"};

    private final ObjectProvider<FundShardEndpointSupport> support;
    private final ObjectProvider<ShardProbeSupport> probe;
    private final ObjectProvider<FundShardPreBootstrap> bootstrap;
    private final AccountingService accountingService;

    public AccountingShardEndpoint(ObjectProvider<FundShardEndpointSupport> support,
                                   ObjectProvider<ShardProbeSupport> probe,
                                   ObjectProvider<FundShardPreBootstrap> bootstrap,
                                   AccountingService accountingService) {
        this.support = support;
        this.probe = probe;
        this.bootstrap = bootstrap;
        this.accountingService = accountingService;
    }

    @ReadOperation
    public Map<String, Object> status() {
        FundShardEndpointSupport s = support.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("domain", AccountingShardConfig.DOMAIN);
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
        } else {
            FundShardPreBootstrap b = bootstrap.getIfAvailable();
            if (b != null) {
                s.bootstrapStats(b.stats());
            }
            out.putAll(s.status());
        }
        out.put("trial", trial());
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
            case "trial":
                return trial();
            default:
                return Map.of("error", "未知视图: " + view, "available", List.of("shadowCompare", "shardSummary", "trial"));
        }
    }

    @ReadOperation
    public Object detail(@Selector String view, @Selector String arg) {
        if (!"account".equals(view)) {
            return Map.of("error", "未知视图: " + view, "available", List.of("account"));
        }
        ShardProbeSupport p = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", arg);
        if (p == null) {
            out.put("shardingConfigured", false);
            return out;
        }
        out.put("expectedShard", ShardKeys.shardOf(arg, p.runtime().shardCount()));
        Map<String, Object> balances = new LinkedHashMap<>();
        Map<String, Object> entries = new LinkedHashMap<>();
        for (int k = 0; k < p.runtime().shardCount(); k++) {
            balances.put(ShardKeys.physicalTable(BALANCE, k), p.scalarSingle(
                    "SELECT COUNT(*) FROM " + ShardKeys.physicalTable(BALANCE, k) + " WHERE account_id=?", arg));
            entries.put(ShardKeys.physicalTable(ENTRY, k), p.scalarSingle(
                    "SELECT COUNT(*) FROM " + ShardKeys.physicalTable(ENTRY, k) + " WHERE account_id=?", arg));
        }
        out.put("balanceDistribution", balances);
        out.put("entryDistribution", entries);
        out.put("legacyBalance", p.rowsSingle("SELECT account_id,balance,frozen FROM " + BALANCE + " WHERE account_id=?", arg));
        out.put("routedBalance", p.rowsRouted("SELECT account_id,balance,frozen FROM " + BALANCE + " WHERE account_id=?", arg));
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
                return s.idgen("E", count, threads);
            case "compare":
                return s.shadowCompare();
            case "resync":
                return s.resync(direction);
            default:
                return s.toggle(enabled);
        }
    }

    private Map<String, Object> trial() {
        TrialDTO dto = accountingService.trial();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalDebit", dto.getTotalDebit());
        out.put("totalCredit", dto.getTotalCredit());
        out.put("balanced", dto.getBalanced());
        out.put("balanceSum", dto.getBalanceSum());
        out.put("accounts", dto.getAccounts());
        out.put("entries", dto.getEntries());
        out.put("suspenseBalance", dto.getSuspenseBalance());
        return out;
    }

    private Map<String, Object> shardSummary() {
        ShardProbeSupport p = probe.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardingConfigured", p != null);
        if (p == null) {
            return out;
        }
        int count = p.runtime().shardCount();
        List<Map<String, Object>> balanceShards = new ArrayList<>();
        List<Map<String, Object>> entryShards = new ArrayList<>();
        long[] balSum = new long[BALANCE_EXPR.length];
        long[] entSum = new long[ENTRY_EXPR.length];
        for (int k = 0; k < count; k++) {
            Map<String, Object> b = physical(p, ShardKeys.physicalTable(BALANCE, k), BALANCE_EXPR);
            balanceShards.add(b);
            accumulate(balSum, b);
            Map<String, Object> e = physical(p, ShardKeys.physicalTable(ENTRY, k), ENTRY_EXPR);
            entryShards.add(e);
            accumulate(entSum, e);
        }
        out.put("balanceShards", balanceShards);
        out.put("entryShards", entryShards);
        out.put("balanceShardSum", sums(balSum));
        out.put("entryShardSum", sums(entSum));
        out.put("balanceLegacy", physical(p, BALANCE, BALANCE_EXPR));
        out.put("entryLegacy", physical(p, ENTRY, ENTRY_EXPR));
        out.put("balanceRouted", routed(p, BALANCE, BALANCE_EXPR));
        out.put("entryRouted", routed(p, ENTRY, ENTRY_EXPR));
        out.put("trial", trial());
        return out;
    }

    private static void accumulate(long[] target, Map<String, Object> row) {
        for (int i = 0; i < target.length; i++) {
            target[i] += num(row, "v" + i);
        }
    }

    private static Map<String, Object> physical(ShardProbeSupport p, String table, String[] exprs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        for (int i = 0; i < exprs.length; i++) {
            row.put("v" + i, p.scalarSingle("SELECT " + exprs[i] + " FROM " + table));
        }
        return row;
    }

    private static Map<String, Object> routed(ShardProbeSupport p, String table, String[] exprs) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < exprs.length; i++) {
            sql.append(i == 0 ? "" : ",").append(exprs[i]).append(" AS v").append(i);
        }
        sql.append(" FROM ").append(table);
        List<Map<String, Object>> rows = p.rowsRouted(sql.toString());
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        for (int i = 0; i < exprs.length; i++) {
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