package com.qoder.payment.settle.web;

import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.facade.settle.WithdrawOrderDTO;
import com.qoder.payment.settle.bill.MerchantBillService;
import com.qoder.payment.settle.fee.FeeRuleService;
import com.qoder.payment.settle.mapper.MerchantBillMapper;
import com.qoder.payment.settle.mapper.SettleAggregateMapper;
import com.qoder.payment.settle.service.SettleBatchService;
import com.qoder.payment.settle.service.SettleCycleService;
import com.qoder.payment.settle.service.SettleSupport;
import com.qoder.payment.settle.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ST-x 清结算运维探针 {@code /actuator/ppsettle}（只读，不改任何业务数据）。
 *
 * <ul>
 *   <li>{@code GET /actuator/ppsettle}：缺省费率/周期对照（证明口径未变）+ 批次状态分布 + 账单/提现计数；</li>
 *   <li>{@code GET /actuator/ppsettle/agg/{merchantId}/{bizDate}}：ST-5 账单口径的<b>跨片归并对照</b>
 *       —— 逐物理表 SUM + 各片之和 + 逻辑表顶层 SUM 归并 + 原单表，四者必须一致；</li>
 *   <li>{@code GET /actuator/ppsettle/withdraws}：提现单状态机快照。</li>
 * </ul>
 */
@Component
@Endpoint(id = "ppsettle")
@RequiredArgsConstructor
public class SettleOpsEndpoint {

    private static final String TABLE = "settle_record";
    private static final String GROSS_PAY = "COALESCE(SUM(gross),0)";
    private static final String FEE_NET = "COALESCE(SUM(CASE WHEN type='PAY' THEN fee ELSE -fee END),0)";
    private static final String NET_NET = "COALESCE(SUM(CASE WHEN type='PAY' THEN net ELSE -net END),0)";

    private final FeeRuleService feeRuleService;
    private final SettleCycleService cycleService;
    private final SettleBatchService batchService;
    private final MerchantBillMapper billMapper;
    private final WithdrawService withdrawService;
    private final SettleAggregateMapper aggregateMapper;
    private final SettleSupport support;
    private final DynamicConfig dynamicConfig;
    private final ObjectProvider<ShardProbeSupport> probe;

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", "payment-settle");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("feeBps", feeRuleService.defaultBps());
        defaults.put("feeBpsCodeDefault", ConfigKeys.DEFAULT_FEE_BPS);
        defaults.put("cycleDays", cycleService.defaultCycleDays());
        defaults.put("withdrawFeeBps", dynamicConfig.getInt(WithdrawService.KEY_FEE_BPS, WithdrawService.DEFAULT_FEE_BPS));
        defaults.put("billDir", dynamicConfig.get(MerchantBillService.KEY_BILL_DIR, MerchantBillService.DEFAULT_BILL_DIR));
        out.put("defaults", defaults);
        out.put("configProvider", dynamicConfig.provider());
        out.put("batch", batchService.stats());
        out.put("billCount", billMapper.selectAll().size());
        Map<String, Integer> withdraws = new LinkedHashMap<>();
        for (WithdrawOrderDTO order : withdrawService.list(null)) {
            withdraws.merge(order.getStatus(), 1, Integer::sum);
        }
        out.put("withdrawByStatus", withdraws);
        ShardProbeSupport support = probe.getIfAvailable();
        out.put("shardingConfigured", support != null);
        out.put("shardingActive", support != null && support.runtime().isActive());
        return out;
    }

    @ReadOperation
    public Object view(@Selector String view) {
        if ("withdraws".equals(view)) {
            return withdrawService.list(null);
        }
        if ("bills".equals(view)) {
            return billMapper.selectAll();
        }
        return Map.of("error", "未知视图: " + view, "available", List.of("withdraws", "bills"));
    }

    /** 跨片归并对照：merchantId + bizDate（epoch millis，缺省今日）。 */
    @ReadOperation
    public Object agg(@Selector String view, @Selector String merchantId, @Selector String bizDate) {
        if (!"agg".equals(view)) {
            return Map.of("error", "未知视图: " + view);
        }
        long from = "-".equals(bizDate) ? SettleSupport.today() : SettleSupport.dayStart(Long.parseLong(bizDate));
        long to = from + SettleSupport.DAY_MS;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchantId);
        out.put("bizDate", from);
        out.put("bizDateText", SettleSupport.dayText(from));
        out.put("routed", routed(merchantId, from, to));
        ShardProbeSupport support = probe.getIfAvailable();
        out.put("shardingConfigured", support != null);
        if (support == null) {
            return out;
        }
        String where = " WHERE merchant_id='" + merchantId.replace("'", "") + "'"
                + " AND created_at>=" + from + " AND created_at<" + to;
        int count = support.runtime().shardCount();
        List<Map<String, Object>> shards = new ArrayList<>();
        long[] sum = new long[5];
        for (int k = 0; k < count; k++) {
            String physical = ShardKeys.physicalTable(TABLE, k);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", physical);
            row.put("recordCount", support.scalarSingle("SELECT COUNT(*) FROM " + physical + where));
            row.put("gross", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + physical + where + " AND type='PAY'"));
            row.put("refund", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + physical + where + " AND type='REFUND'"));
            row.put("fee", support.scalarSingle("SELECT " + FEE_NET + " FROM " + physical + where));
            row.put("net", support.scalarSingle("SELECT " + NET_NET + " FROM " + physical + where));
            shards.add(row);
            sum[0] += ((Number) row.get("recordCount")).longValue();
            sum[1] += ((Number) row.get("gross")).longValue();
            sum[2] += ((Number) row.get("refund")).longValue();
            sum[3] += ((Number) row.get("fee")).longValue();
            sum[4] += ((Number) row.get("net")).longValue();
        }
        Map<String, Object> shardSum = new LinkedHashMap<>();
        shardSum.put("recordCount", sum[0]);
        shardSum.put("gross", sum[1]);
        shardSum.put("refund", sum[2]);
        shardSum.put("fee", sum[3]);
        shardSum.put("net", sum[4]);
        out.put("shards", shards);
        out.put("shardSum", shardSum);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("recordCount", support.scalarSingle("SELECT COUNT(*) FROM " + TABLE + where));
        legacy.put("gross", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + TABLE + where + " AND type='PAY'"));
        legacy.put("refund", support.scalarSingle("SELECT " + GROSS_PAY + " FROM " + TABLE + where + " AND type='REFUND'"));
        legacy.put("fee", support.scalarSingle("SELECT " + FEE_NET + " FROM " + TABLE + where));
        legacy.put("net", support.scalarSingle("SELECT " + NET_NET + " FROM " + TABLE + where));
        out.put("legacySingleTable", legacy);
        out.put("expectedShard", ShardKeys.shardOf(merchantId, count));
        return out;
    }

    /** 配置热更立即生效（local-file 重读文件；Apollo 长轮询自动推送返回 false）。只刷配置，不动业务数据。 */
    @org.springframework.boot.actuate.endpoint.annotation.WriteOperation
    public Map<String, Object> refresh(@org.springframework.lang.Nullable String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action == null ? "refresh" : action);
        out.put("provider", dynamicConfig.provider());
        out.put("refreshed", dynamicConfig.refresh());
        out.put("feeBps", feeRuleService.defaultBps());
        out.put("cycleDays", cycleService.defaultCycleDays());
        out.put("settleFeeDefaultBps", dynamicConfig.get(FeeRuleService.KEY_DEFAULT_BPS, null));
        return out;
    }

    private Map<String, Object> routed(String merchantId, long from, long to) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordCount", support.readYourWrite(() -> aggregateMapper.countRange(merchantId, from, to)));
        row.put("gross", support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumPayGross(merchantId, from, to))));
        row.put("refund", support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumRefundGross(merchantId, from, to))));
        row.put("fee", support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumFee(merchantId, from, to))));
        row.put("net", support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumNet(merchantId, from, to))));
        return row;
    }
}