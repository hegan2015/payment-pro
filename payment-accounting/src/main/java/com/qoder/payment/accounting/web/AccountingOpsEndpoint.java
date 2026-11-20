package com.qoder.payment.accounting.web;

import com.qoder.payment.accounting.config.AccountingSchemaUpgrade;
import com.qoder.payment.accounting.dataobject.BatchRunDO;
import com.qoder.payment.accounting.dataobject.SubjectBalanceDO;
import com.qoder.payment.accounting.daycut.AccountingDayClock;
import com.qoder.payment.accounting.daycut.AccountingDayService;
import com.qoder.payment.accounting.daycut.DayEndBatchService;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.accounting.mapper.SubjectBalanceMapper;
import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.accounting.service.ReserveDiff;
import com.qoder.payment.accounting.subject.AccountSubjectMapping;
import com.qoder.payment.accounting.subject.SubjectService;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.facade.accounting.TrialDTO;
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
 * P1-E 账务工作流运维探针 {@code /actuator/ppaccounting}（只读 + 配置 reload，不改任何业务数据）。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppaccounting}：科目/映射/会计日/日切开关/批处理留痕/试算平衡总览；</li>
 *   <li>{@code GET /actuator/ppaccounting/mapping}：<b>既有系统户 → 科目映射对照 + 实时余额</b>
 *       （证明科目体系接入后余额与记账口径一字未变）；</li>
 *   <li>{@code GET /actuator/ppaccounting/subjectAgg/{bizDate}}：当日发生额<b>跨片归并四路对照</b>
 *       —— 逐物理表 SUM + 各片之和 + 逻辑表顶层 SUM + GROUP BY 分片键汇总之和，四者必须一致；</li>
 *   <li>{@code GET /actuator/ppaccounting/batchRuns}：批处理状态机留痕（含 attempt，失败可重跑证据）；</li>
 *   <li>{@code GET /actuator/ppaccounting/daySnapshot/{bizNo}}：在途 TCC 受理日快照；</li>
 *   <li>{@code POST} {"action":"refresh"}：DynamicConfig 手动 reload（local 文件配置热更）。</li>
 * </ul>
 */
@Component
@Endpoint(id = "ppaccounting")
@RequiredArgsConstructor
public class AccountingOpsEndpoint {

    private static final String ENTRY = "ledger_entry";
    private static final String DEBIT_EXPR = "SUM(CASE WHEN dir='D' THEN amount ELSE 0 END)";
    private static final String CREDIT_EXPR = "SUM(CASE WHEN dir='C' THEN amount ELSE 0 END)";

    private final SubjectService subjectService;
    private final AccountingDayService dayService;
    private final DayEndBatchService dayEndBatchService;
    private final AccountingDayClock clock;
    private final AccountingService accountingService;
    private final EntryMapper entryMapper;
    private final SubjectBalanceMapper subjectBalanceMapper;
    private final DynamicConfig dynamicConfig;
    private final ObjectProvider<ShardProbeSupport> probe;

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", "payment-accounting");
        out.put("cards", List.of("AC-2", "AC-3", "AC-4", "AC-5"));
        out.put("subjects", subjectService.listSubjects().size());
        out.put("subjectSeeded", subjectService.seeded());
        out.put("mappedAccounts", subjectService.mappedAccounts());
        out.put("newSingleTables", AccountingSchemaUpgrade.newSingleTables());
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("current", clock.currentDay());
        day.put("currentText", AccountingDayClock.dayText(clock.currentDay()));
        day.put("cutAt", clock.cutAt());
        day.put("autoEnabled", clock.autoCutEnabled());
        day.put("profitTransferEnabled", dayEndBatchService.profitTransferEnabled());
        day.put("days", dayService.list(5));
        out.put("dayCut", day);
        out.put("configProvider", dynamicConfig.provider());
        Map<String, Object> reserve = new LinkedHashMap<>();
        reserve.put("channels", dynamicConfig.get(ReserveDiff.KEY_CHANNELS, ReserveDiff.DEFAULT_CHANNELS));
        reserve.put("warnThreshold", dynamicConfig.getLong(ReserveDiff.KEY_WARN, ReserveDiff.DEFAULT_WARN));
        reserve.put("criticalThreshold", dynamicConfig.getLong(ReserveDiff.KEY_CRITICAL, ReserveDiff.DEFAULT_CRITICAL));
        out.put("reserveRecon", reserve);
        out.put("batchRuns", batchRuns(10));
        out.put("trial", trial());
        ShardProbeSupport p = probe.getIfAvailable();
        out.put("shardingConfigured", p != null);
        out.put("shardingActive", p != null && p.runtime().isActive());
        return out;
    }

    @ReadOperation
    public Object view(@Selector String view) {
        switch (view) {
            case "mapping":
                return mapping();
            case "batchRuns":
                return batchRuns(50);
            case "subjects":
                return subjectService.listSubjects();
            default:
                return Map.of("error", "未知视图: " + view,
                        "available", List.of("mapping", "batchRuns", "subjects", "subjectAgg/{bizDate}", "daySnapshot/{bizNo}"));
        }
    }

    @ReadOperation
    public Object detail(@Selector String view, @Selector String arg) {
        switch (view) {
            case "subjectAgg":
                return subjectAgg(Long.parseLong(arg));
            case "daySnapshot":
                return daySnapshot(arg);
            case "subjectBalance":
                return subjectBalanceMapper.selectByDate(Long.parseLong(arg));
            default:
                return Map.of("error", "未知视图: " + view);
        }
    }

    @WriteOperation
    public Map<String, Object> write(@Nullable String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        String op = action == null ? "refresh" : action;
        if ("refresh".equals(op)) {
            out.put("refreshed", dynamicConfig.refresh());
            out.put("provider", dynamicConfig.provider());
            out.put("cutAt", clock.cutAt());
            out.put("autoEnabled", clock.autoCutEnabled());
            out.put("profitTransferEnabled", dayEndBatchService.profitTransferEnabled());
            return out;
        }
        out.put("error", "未知 action: " + op);
        out.put("available", List.of("refresh"));
        return out;
    }

    // ------------------------------------------------------------------ 视图

    /** 系统户 → 科目映射对照 + 实时余额（口径未变证据）。 */
    private Map<String, Object> mapping() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccountSubjectMapping.Def def : AccountSubjectMapping.catalog()) {
            if (def.systemAccount() == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("systemAccount", def.systemAccount());
            row.put("subjectCode", def.code());
            row.put("subjectName", def.name());
            row.put("type", def.type());
            row.put("direction", def.direction());
            row.put("resolvedSubject", subjectService.subjectOf(def.systemAccount()));
            row.put("balance", accountingService.balance(def.systemAccount()));
            rows.add(row);
        }
        out.put("systemMappings", rows);
        out.put("customerDefaultSubject", AccountSubjectMapping.CUSTOMER_BALANCE);
        out.put("unmappedFallbackSubject", AccountSubjectMapping.UNMAPPED);
        out.put("accountMappings", subjectService.mappings());
        out.put("trial", trial());
        return out;
    }

    /**
     * 当日发生额跨片归并四路对照（I-5a/b 踩坑护栏：SUM 必须顶层投影）。
     * 逐物理表 / 各片之和 / 逻辑表顶层 SUM / GROUP BY 分片键汇总之和 / 原单表，必须逐值一致。
     */
    private Map<String, Object> subjectAgg(long bizDate) {
        long day = AccountingDayClock.dayStart(bizDate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bizDate", day);
        String where = "biz_date=" + day;
        ShardProbeSupport p = probe.getIfAvailable();
        out.put("shardingConfigured", p != null);
        long debitGrouped = 0;
        long creditGrouped = 0;
        for (var row : entryMapper.sumByAccountForDay(day)) {
            debitGrouped += row.getDebitTotal() == null ? 0 : row.getDebitTotal();
            creditGrouped += row.getCreditTotal() == null ? 0 : row.getCreditTotal();
        }
        out.put("groupByShardKey", Map.of("debit", debitGrouped, "credit", creditGrouped));
        out.put("logicTopLevelSum", Map.of(
                "debit", nz(entryMapper.sumDebitForDay(day)),
                "credit", nz(entryMapper.sumCreditForDay(day)),
                "entries", entryMapper.countForDay(day)));
        out.put("legacyEntries", entryMapper.countLegacy());
        Map<String, SubjectBalanceDO> computed = dayEndBatchService.compute(day);
        long subjectDebit = 0;
        long subjectCredit = 0;
        long subjectBalance = 0;
        for (SubjectBalanceDO row : computed.values()) {
            subjectDebit += nz(row.getDebitTotal());
            subjectCredit += nz(row.getCreditTotal());
            subjectBalance += nz(row.getBalance());
        }
        out.put("subjectRollup", Map.of("debit", subjectDebit, "credit", subjectCredit,
                "balanceSum", subjectBalance, "subjects", computed.size()));
        if (p != null) {
            int count = p.runtime().shardCount();
            List<Map<String, Object>> shards = new ArrayList<>();
            long shardDebit = 0;
            long shardCredit = 0;
            for (int k = 0; k < count; k++) {
                long d = p.scalarPhysical(ENTRY, k, DEBIT_EXPR, where);
                long c = p.scalarPhysical(ENTRY, k, CREDIT_EXPR, where);
                shards.add(Map.of("table", ShardKeys.physicalTable(ENTRY, k), "debit", d, "credit", c));
                shardDebit += d;
                shardCredit += c;
            }
            out.put("physicalShards", shards);
            out.put("shardSum", Map.of("debit", shardDebit, "credit", shardCredit));
            out.put("legacySingleTable", Map.of(
                    "debit", p.scalarSingle("SELECT " + DEBIT_EXPR + " FROM " + ENTRY + " WHERE " + where),
                    "credit", p.scalarSingle("SELECT " + CREDIT_EXPR + " FROM " + ENTRY + " WHERE " + where)));
            List<Map<String, Object>> routed = p.rowsRouted("SELECT " + DEBIT_EXPR + " AS d, " + CREDIT_EXPR
                    + " AS c FROM " + ENTRY + " WHERE " + where);
            Map<String, Object> first = routed.isEmpty() ? Map.of() : routed.get(0);
            out.put("routedMerge", Map.of("debit", num(first, "d"), "credit", num(first, "c"),
                    "rows", routed.size()));
        }
        return out;
    }

    private Map<String, Object> daySnapshot(String bizNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bizNo", bizNo);
        out.put("resolvedBizDate", clock.dayFor(bizNo));
        out.put("currentDay", clock.currentDay());
        out.put("voucher", accountingService.transfer(bizNo));
        return out;
    }

    private List<Map<String, Object>> batchRuns(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BatchRunDO run : dayService.batchRuns(null, null, limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.getId());
            row.put("batchType", run.getBatchType());
            row.put("bizDate", run.getBizDate());
            row.put("shardIndex", run.getShardIndex());
            row.put("shardTotal", run.getShardTotal());
            row.put("status", run.getStatus());
            row.put("step", run.getStep());
            row.put("attempt", run.getAttempt());
            row.put("error", run.getError());
            row.put("startedAt", run.getStartedAt());
            row.put("finishedAt", run.getFinishedAt());
            out.add(row);
        }
        return out;
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

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}