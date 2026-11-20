package com.qoder.payment.recon.service;

import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.recon.dataobject.ReconBatchDO;
import com.qoder.payment.recon.dataobject.ReconProgressDO;
import com.qoder.payment.recon.mapper.ReconBatchMapper;
import com.qoder.payment.recon.mapper.ReconProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * RC-2 日切批量对账：按会计日批量核对六项（口径与实时一致，复用 {@link ReconService}），
 * 批次状态机 PENDING→RUNNING→SUCCESS/FAILED + 幂等（同会计日重跑复用同一批次行，attempt 递增，
 * 差错单按 (DISPUTE,RECON:checkName:bizDate:bizNo) 去重不重复建单）。
 *
 * <p><b>会计日取数不硬编码当前日期</b>：bizDate 为空取 accounting 当前会计日
 * （{@code currentAccountingDay}，共享栈可能已被手动日切推进）；支付单按 created_at 归日过滤。
 *
 * <p><b>默认不自动跑批</b>：无 @Scheduled 周期触发，由 XXL-Job reconDaily（分片广播）/
 * 控制台 / ppjob 触发，与 recon/settle/accounting 先例一致。多实例互斥由
 * {@link DailyReconJob} 逐片 ShedLock 保证。
 *
 * <p>RC-5 分片：各片按支付单号 CRC32 取模（{@link ReconService#shardOf}）不重不漏，
 * 全局聚合项（试算平衡）只由分片 0 核对；各片进度落 recon_progress，Java 归并汇总。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReconService {

    public static final String TYPE_DAILY = "DAILY";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReconService reconService;
    private final ReconBatchMapper batchMapper;
    private final ReconProgressMapper progressMapper;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    /** 解析会计日：null=当前账面会计日；非 null 归一到当日 00:00（Asia/Shanghai）。 */
    public long resolveDay(Long bizDate) {
        if (bizDate == null) {
            Long current = accountingFacade.currentAccountingDay().orThrow().getBizDate();
            if (current == null) {
                throw new IllegalStateException("账务域未返回当前会计日");
            }
            return ReconService.dayOf(current);
        }
        return ReconService.dayOf(bizDate);
    }

    /** 支持 yyyyMMdd 文本参数（调度中心 param 友好）。 */
    public static Long parseDayText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String raw = text.trim();
        try {
            if (raw.length() == 8) {
                LocalDate day = LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
                return day.atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
            return Long.parseLong(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("会计日参数非法（需 epoch millis 或 yyyyMMdd）: " + text);
        }
    }

    /** 单片全量日切对账（控制台入口）。 */
    public ReconReportDTO runDaily(Long bizDate) {
        return runDailySharded(bizDate, 0, 1);
    }

    /** 分片日切对账（XXL-Job 分片广播入口，逐片锁由 DailyReconJob 持有）。 */
    public ReconReportDTO runDailySharded(Long bizDate, int shardIndex, int shardTotal) {
        long day = resolveDay(bizDate);
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        ReconBatchDO batch = startBatch(day, TYPE_DAILY, "", index, total);
        long scoped = scopedCount(day, index, total);
        upsertProgress(day, TYPE_DAILY, index, total, scoped, 0, 0, 0, 0, "RUNNING");
        try {
            ReconReportDTO report = reconService.runDailySharded(day, index, total);
            boolean pass = Boolean.TRUE.equals(report.getPass());
            int disputes = report.getDisputeIds() == null ? 0 : report.getDisputeIds().size();
            upsertProgress(day, TYPE_DAILY, index, total, scoped, scoped, pass ? 1 : 0, pass ? 0 : 1,
                    disputes, pass ? "SUCCESS" : "FAILED");
            batchMapper.finish(batch.getId(), pass ? "SUCCESS" : "FAILED", report.getId(),
                    (int) scoped, (int) scoped, pass ? 1 : 0, pass ? 0 : 1, disputes,
                    truncate("会计日 " + ReconService.dayText(day) + " 分片 " + index + "/" + total
                            + " 六项核对" + (pass ? "全部通过" : "存在失败项") + "，差错单 " + disputes + " 个"),
                    System.currentTimeMillis());
            MetricsSupport.count("recon_daily_run", "type", "daily", "result", pass ? "pass" : "fail");
            log.info("RC-2 日切对账完成 batch={} bizDate={} shard={}/{} pass={} report={} disputes={}",
                    batch.getId(), day, index, total, pass, report.getId(), disputes);
            return report;
        } catch (Exception e) {
            upsertProgress(day, TYPE_DAILY, index, total, scoped, 0, 0, 1, 0, "FAILED");
            batchMapper.finish(batch.getId(), "FAILED", null, (int) scoped, 0, 0, 1, 0,
                    truncate("执行异常: " + e.getMessage()), System.currentTimeMillis());
            MetricsSupport.count("recon_daily_run", "type", "daily", "result", "error");
            log.error("RC-2 日切对账失败 batch={} bizDate={} shard={}/{}（可重跑幂等续跑）",
                    batch.getId(), day, index, total, e);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    /** 本片范围内的支付单数（SUCCESS+FAILED，与 ReconService 采集口径一致；PROCESSING 在途不参与）。 */
    private long scopedCount(long day, int index, int total) {
        List<PaymentDTO> all = paymentFacade.listPayments().orThrow();
        return all.stream()
                .filter(p -> "SUCCESS".equals(p.getStatus()) || "FAILED".equals(p.getStatus()))
                .filter(p -> ReconService.dayOf(p.getCreatedAt()) == day)
                .filter(p -> total == 1 || ReconService.shardOf(p.getId(), total) == index)
                .count();
    }

    /** 批次幂等载体：存在即复用（attempt 递增），不存在即建；并发唯一键冲突回落复用。 */
    ReconBatchDO startBatch(long day, String type, String channelCode, int index, int total) {
        ReconBatchDO existing = batchMapper.selectUnique(day, type, channelCode, index, total);
        long now = System.currentTimeMillis();
        if (existing != null) {
            batchMapper.markRunning(existing.getId(), "重跑（幂等续跑，不重复建单）", now);
            existing.setStatus("RUNNING");
            existing.setAttempt(existing.getAttempt() == null ? 1 : existing.getAttempt() + 1);
            return existing;
        }
        ReconBatchDO batch = new ReconBatchDO();
        batch.setId("RB" + batchMapper.nextId());
        batch.setBizDate(day);
        batch.setReconType(type);
        batch.setChannelCode(channelCode);
        batch.setShardIndex(index);
        batch.setShardTotal(total);
        batch.setStatus("RUNNING");
        batch.setAttempt(1);
        batch.setTotal(0);
        batch.setChecked(0);
        batch.setPassed(0);
        batch.setFailed(0);
        batch.setDisputeCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        try {
            batchMapper.insert(batch);
            return batch;
        } catch (DuplicateKeyException dup) {
            ReconBatchDO winner = batchMapper.selectUnique(day, type, channelCode, index, total);
            batchMapper.markRunning(winner.getId(), "重跑（并发幂等回落）", now);
            return winner;
        }
    }

    void upsertProgress(long day, String type, int index, int total, long totalCount, long checked,
                        int passed, int failed, long disputes, String status) {
        ReconProgressDO row = new ReconProgressDO();
        row.setBizDate(day);
        row.setReconType(type);
        row.setShardIndex(index);
        row.setShardTotal(total);
        row.setTotal(totalCount);
        row.setChecked(checked);
        row.setPassed(passed);
        row.setFailed(failed);
        row.setDisputeCount(disputes);
        row.setStatus(status);
        row.setUpdatedAt(System.currentTimeMillis());
        ReconProgressDO existing = progressMapper.selectUnique(day, type, index, total);
        if (existing == null) {
            try {
                progressMapper.insert(row);
                return;
            } catch (DuplicateKeyException dup) {
                // 并发回落更新
            }
        }
        progressMapper.update(row);
    }

    private static String truncate(String v) {
        return v == null ? null : (v.length() <= 512 ? v : v.substring(0, 512));
    }
}