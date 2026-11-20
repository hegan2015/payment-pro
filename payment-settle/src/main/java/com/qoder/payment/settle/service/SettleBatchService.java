package com.qoder.payment.settle.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.settle.SettleBatchDTO;
import com.qoder.payment.facade.settle.SettleBatchRunDTO;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.settle.mapper.SettleAggregateMapper;
import com.qoder.payment.settle.mapper.SettleBatchMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ST-1 T+N 结算跑批（日切 → 到期判定 → 批次状态机 → 净额恒等校验）。
 *
 * <p><b>调度</b>：经 {@code SettleBatchJob}（JobHandler + 逐片 ShedLock）触发，多实例同片互斥；
 * 分片按 {@code ShardKeys.shardOf(merchantId, shardTotal)}（CRC32 取模，与 DB 分片同一算法）
 * 划分商户，<b>不重不漏</b>。
 *
 * <p><b>幂等</b>：批次创建走 {@code (SETTLE_BATCH, merchantId:bizDate)} + UNIQUE(merchant_id,biz_date)；
 * 明细 UNIQUE(batch_id,record_id) 只 INSERT。已 SETTLED 批次直接跳过，重复跑批不重结。
 *
 * <p><b>资金口径</b>：现状支付受理即已复式记账入账商户（D 付款方 / C 商户 + SYS_FEE），
 * T+N 批次是「结算单批次化 + 净额确认」，<b>不重复动账</b>（与 IMPLEMENTATION-PLAN §3.F ST-1⑥ 一致）；
 * 结算侧真正的新增动账在 ST-3 分账与 ST-4 出金，均走 AccountingFacade 复式记账 + 各自幂等键。
 * 批次净额恒等由「Java 逐笔求和 == 跨片顶层 SUM 归并」双向校验，任一不符即 FAILED 不落已结算。
 *
 * <p><b>不改 settle_record</b>：结算单保持不可变（影子副本逐列一致 ⇒ 分片回滚后逐条一致），
 * 批次归属登记在单库表 settle_batch_item。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleBatchService {

    public static final String KEY_LOOKBACK_DAYS = "settle.batch.lookback-days";
    public static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final SettleBatchMapper batchMapper;
    private final SettleAggregateMapper aggregateMapper;
    private final SettleCycleService cycleService;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;

    public List<SettleBatchDTO> list(String merchantId, Long bizDate) {
        List<SettleBatchDTO> batches = support.readYourWrite(() -> {
            if (merchantId != null && !merchantId.isBlank() && bizDate != null) {
                SettleBatchDTO one = batchMapper.selectByKey(merchantId, bizDate);
                return one == null ? List.<SettleBatchDTO>of() : List.of(one);
            }
            return merchantId == null || merchantId.isBlank()
                    ? batchMapper.selectAll() : batchMapper.selectByMerchant(merchantId);
        });
        List<SettleBatchDTO> out = new ArrayList<>();
        for (SettleBatchDTO batch : batches) {
            if (bizDate != null && !bizDate.equals(batch.getBizDate())) {
                continue;
            }
            out.add(decorate(batch));
        }
        return out;
    }

    public SettleBatchDTO get(String batchId) {
        SettleBatchDTO batch = support.readYourWrite(() -> batchMapper.selectById(batchId));
        if (batch == null) {
            throw new BizException(ResultCode.NOT_FOUND, "结算批次不存在: " + batchId);
        }
        return decorate(batch);
    }

    public SettleBatchRunDTO run(Long bizDate, int shardIndex, int shardTotal, boolean force) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        long today = SettleSupport.today();
        int lookback = Math.max(1, dynamicConfig.getInt(KEY_LOOKBACK_DAYS, DEFAULT_LOOKBACK_DAYS));
        long from = bizDate != null ? SettleSupport.dayStart(bizDate)
                : today - (long) lookback * SettleSupport.DAY_MS;
        long to = bizDate != null ? SettleSupport.dayStart(bizDate) + SettleSupport.DAY_MS
                : today + SettleSupport.DAY_MS;

        SettleBatchRunDTO result = new SettleBatchRunDTO();
        result.setBizDate(bizDate);
        result.setShardIndex(index);
        result.setShardTotal(total);
        result.setMerchants(0);
        result.setCreated(0);
        result.setSettled(0);
        result.setFailed(0);
        result.setNotDue(0);
        result.setSkipped(0);

        // 跨片原始投影（不带 shard key ⇒ 归并引擎拼接各片），商户去重 + 自然日分桶在 Java 侧完成
        List<Map<String, Object>> rows = support.readYourWrite(() -> aggregateMapper.merchantDays(from, to));
        Map<String, TreeSet<Long>> byMerchant = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            Object merchant = row.get("merchant_id");
            Object createdAt = row.get("created_at");
            if (merchant == null || !(createdAt instanceof Number number)) {
                continue;
            }
            byMerchant.computeIfAbsent(String.valueOf(merchant), k -> new TreeSet<>())
                    .add(SettleSupport.dayStart(number.longValue()));
        }

        for (Map.Entry<String, TreeSet<Long>> entry : byMerchant.entrySet()) {
            String merchantId = entry.getKey();
            if (ShardKeys.shardOf(merchantId, total) != index) {
                continue;
            }
            result.setMerchants(result.getMerchants() + 1);
            int cycleDays = cycleService.cycleDaysOf(merchantId);
            for (long day : entry.getValue()) {
                long dueAt = day + (long) cycleDays * SettleSupport.DAY_MS;
                if (!force && today < dueAt) {
                    result.setNotDue(result.getNotDue() + 1);
                    continue;
                }
                SettleBatchDTO settled = support.readYourWrite(() -> batchMapper.selectSettled(merchantId, day));
                if (settled != null) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.getBatches().add(decorate(settled));
                    continue;
                }
                SettleBatchDTO batch = settleOne(merchantId, day, cycleDays, index, total);
                result.getBatches().add(decorate(batch));
                if ("SETTLED".equals(batch.getStatus())) {
                    result.setSettled(result.getSettled() + 1);
                } else {
                    result.setFailed(result.getFailed() + 1);
                }
                result.setCreated(result.getCreated() + 1);
            }
        }
        log.info("ST-1 跑批完成 shard={}/{} bizDate={} merchants={} created={} settled={} failed={} notDue={} skipped={}",
                index, total, bizDate, result.getMerchants(), result.getCreated(), result.getSettled(),
                result.getFailed(), result.getNotDue(), result.getSkipped());
        return result;
    }

    /** 单商户单业务日结算：幂等创建批次（PENDING）→ 驱动状态机。 */
    private SettleBatchDTO settleOne(String merchantId, long day, int cycleDays, int index, int total) {
        String bizNo = merchantId + ":" + day;
        idempotentTemplate.execute("SETTLE_BATCH", bizNo, SettleBatchDTO.class, () -> {
            SettleBatchDTO existing = batchMapper.selectByKey(merchantId, day);
            if (existing != null) {
                return existing;
            }
            SettleBatchDTO batch = new SettleBatchDTO();
            batch.setId(support.newId("B"));
            batch.setMerchantId(merchantId);
            batch.setBizDate(day);
            batch.setCycleDays(cycleDays);
            batch.setGross(0L);
            batch.setFee(0L);
            batch.setNet(0L);
            batch.setRefund(0L);
            batch.setRecordCount(0);
            batch.setStatus("PENDING");
            batch.setShardIndex(index);
            batch.setShardTotal(total);
            batch.setCreatedAt(System.currentTimeMillis());
            batchMapper.insert(batch);
            return batch;
        });
        SettleBatchDTO current = support.readYourWrite(() -> batchMapper.selectByKey(merchantId, day));
        if (current == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "批次创建后不可见: " + bizNo);
        }
        if ("SETTLED".equals(current.getStatus())) {
            return current;
        }
        return drive(current);
    }

    /** PENDING/FAILED/SETTLING → SETTLING → SETTLED / FAILED（可重跑续做，绝不重结）。 */
    private SettleBatchDTO drive(SettleBatchDTO batch) {
        String merchantId = batch.getMerchantId();
        long from = batch.getBizDate();
        long to = from + SettleSupport.DAY_MS;
        batchMapper.updateStatus(batch.getId(), "SETTLING", null);
        try {
            List<SettleRecordDTO> records = support.readYourWrite(() -> aggregateMapper.selectRange(merchantId, from, to));
            long gross = 0;
            long refund = 0;
            long fee = 0;
            long net = 0;
            for (SettleRecordDTO record : records) {
                if ("PAY".equals(record.getType())) {
                    gross += SettleSupport.nz(record.getGross());
                    fee += SettleSupport.nz(record.getFee());
                    net += SettleSupport.nz(record.getNet());
                } else {
                    refund += SettleSupport.nz(record.getGross());
                    fee -= SettleSupport.nz(record.getFee());
                    net -= SettleSupport.nz(record.getNet());
                }
            }
            // 跨片顶层 SUM 归并对照（分片态各片 SUM 再相加；单库态同一 SQL 同一结果）
            long sqlGross = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumPayGross(merchantId, from, to)));
            long sqlRefund = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumRefundGross(merchantId, from, to)));
            long sqlFee = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumFee(merchantId, from, to)));
            long sqlNet = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumNet(merchantId, from, to)));
            int sqlCount = support.readYourWrite(() -> aggregateMapper.countRange(merchantId, from, to));
            String mismatch = null;
            if (gross != sqlGross || refund != sqlRefund || fee != sqlFee || net != sqlNet || records.size() != sqlCount) {
                mismatch = "跨片归并与逐笔求和不一致 java(" + gross + "," + refund + "," + fee + "," + net + ","
                        + records.size() + ") sql(" + sqlGross + "," + sqlRefund + "," + sqlFee + "," + sqlNet + ","
                        + sqlCount + ")";
            } else if (net != gross - refund - fee) {
                mismatch = "净额恒等式不成立 net=" + net + " gross=" + gross + " refund=" + refund + " fee=" + fee;
            }
            if (mismatch != null) {
                log.error("[告警] ST-1 批次净额校验失败 batchId={} merchant={} bizDate={} {}",
                        batch.getId(), merchantId, from, mismatch);
                return finish(batch, "FAILED", gross, fee, net, refund, records.size(), mismatch);
            }

            Set<String> batched = new HashSet<>(batchMapper.selectBatchedRecordIds(merchantId));
            long now = System.currentTimeMillis();
            for (SettleRecordDTO record : records) {
                if (batched.contains(record.getId())) {
                    continue;
                }
                boolean pay = "PAY".equals(record.getType());
                long itemFee = pay ? SettleSupport.nz(record.getFee()) : -SettleSupport.nz(record.getFee());
                long itemNet = pay ? SettleSupport.nz(record.getNet()) : -SettleSupport.nz(record.getNet());
                try {
                    batchMapper.insertItem(support.newId("I"), batch.getId(), record.getId(), merchantId,
                            record.getType(), SettleSupport.nz(record.getGross()), itemFee, itemNet, now);
                } catch (DuplicateKeyException e) {
                    // 并发/重跑：唯一键 (batch_id,record_id) 吸收，不重不漏
                    log.info("ST-1 批次明细幂等命中 batchId={} recordId={}", batch.getId(), record.getId());
                }
            }
            int items = batchMapper.countItems(batch.getId());
            long itemNet = SettleSupport.nz(batchMapper.sumItemNet(batch.getId()));
            if (items != records.size() || itemNet != net) {
                String reason = "批次明细守恒失败 items=" + items + " records=" + records.size()
                        + " itemNet=" + itemNet + " net=" + net;
                log.error("[告警] ST-1 {}", reason);
                return finish(batch, "FAILED", gross, fee, net, refund, records.size(), reason);
            }
            return finish(batch, "SETTLED", gross, fee, net, refund, records.size(), null);
        } catch (Exception e) {
            log.error("[告警] ST-1 批次结算异常 batchId={} merchant={}", batch.getId(), merchantId, e);
            return finish(batch, "FAILED", SettleSupport.nz(batch.getGross()), SettleSupport.nz(batch.getFee()),
                    SettleSupport.nz(batch.getNet()), SettleSupport.nz(batch.getRefund()),
                    batch.getRecordCount() == null ? 0 : batch.getRecordCount(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private SettleBatchDTO finish(SettleBatchDTO batch, String status, long gross, long fee, long net,
                                  long refund, int recordCount, String failReason) {
        batch.setStatus(status);
        batch.setGross(gross);
        batch.setFee(fee);
        batch.setNet(net);
        batch.setRefund(refund);
        batch.setRecordCount(recordCount);
        batch.setFailReason(failReason);
        batch.setSettledAt("SETTLED".equals(status) ? System.currentTimeMillis() : null);
        batchMapper.finish(batch);
        log.info("ST-1 批次终态 batchId={} merchant={} bizDate={} status={} gross={} fee={} net={} refund={} count={}",
                batch.getId(), batch.getMerchantId(), batch.getBizDate(), status, gross, fee, net, refund, recordCount);
        return batch;
    }

    private static SettleBatchDTO decorate(SettleBatchDTO batch) {
        if (batch != null && batch.getBizDate() != null) {
            batch.setBizDateText(SettleSupport.dayText(batch.getBizDate()));
        }
        return batch;
    }

    /** 批次汇总（运维探针用）：状态分布 + 净额合计。 */
    public Map<String, Object> stats() {
        List<SettleBatchDTO> all = support.readYourWrite(batchMapper::selectAll);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        long net = 0;
        long fee = 0;
        for (SettleBatchDTO batch : all) {
            byStatus.merge(batch.getStatus(), 1, Integer::sum);
            if ("SETTLED".equals(batch.getStatus())) {
                net += SettleSupport.nz(batch.getNet());
                fee += SettleSupport.nz(batch.getFee());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("byStatus", byStatus);
        out.put("settledNet", net);
        out.put("settledFee", fee);
        return out;
    }
}