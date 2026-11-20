package com.qoder.payment.accounting.daycut;

import com.qoder.payment.accounting.dataobject.AccountingDayDO;
import com.qoder.payment.accounting.dataobject.BatchRunDO;
import com.qoder.payment.accounting.dataobject.DayEndReportDO;
import com.qoder.payment.accounting.mapper.AccountingDayMapper;
import com.qoder.payment.accounting.mapper.BatchRunMapper;
import com.qoder.payment.accounting.mapper.BalanceMapper;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.accounting.AccountingDayDTO;
import com.qoder.payment.facade.accounting.DayEndReportDTO;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AC-3 会计日切：OPEN → CUTTING → 快照 → 科目汇总/日终报告 → CLOSED → 次日 OPEN。
 *
 * <p><b>资金护航</b>：
 * <ul>
 *   <li>日切<b>只切会计日标签，不动任何余额</b>；切前切后逐账户余额校验和必须一致
 *       （开启损益结转时只允许损益户变动，且 Σbalance 恒 0）；</li>
 *   <li>状态机 CAS（{@code UPDATE ... WHERE status=?}）+ ShedLock 全局锁
 *       {@code accountingDayCut:<bizDate>} ⇒ 多实例不重复日切；</li>
 *   <li>批处理留痕 accounting_batch_run（RUNNING/SUCCESS/FAILED + attempt），失败可重跑幂等
 *       （CUTTING 中间态可续跑，派生行 upsert，结转凭证 (DAY_CUT,bizDate) 幂等）；</li>
 *   <li>在途 TCC 用受理时快照（{@link AccountingDayClock#snapshot}），日切不破坏在途单收敛。</li>
 * </ul>
 *
 * <p><b>不自动跑批</b>：默认 {@code accounting.daycut.auto-enabled=false}，本地 @Scheduled 静默；
 * 由 XXL-Job / 控制台 / {@code POST /actuator/ppjob/accountingDayCut} 显式触发（与 recon/settle 先例一致），
 * 绝不干扰既有断言。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingDayService {

    public static final String BATCH_DAY_CUT = "DAY_CUT";
    public static final String KEY_AUTO = "accounting.daycut.auto-enabled";
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    private final AccountingDayMapper dayMapper;
    private final BatchRunMapper batchRunMapper;
    private final BalanceMapper balanceMapper;
    private final EntryMapper entryMapper;
    private final AccountingDayClock clock;
    private final DayEndBatchService dayEndBatchService;
    private final DayCutLock dayCutLock;
    private final ObjectProvider<ShardRuntime> shardRuntime;

    // ------------------------------------------------------------------ 查询

    public AccountingDayDTO current() {
        long day = clock.currentDay();
        return toDTO(dayMapper.select(day), null, null);
    }

    public List<AccountingDayDTO> list(int limit) {
        int safe = limit <= 0 ? 10 : Math.min(limit, 200);
        List<AccountingDayDTO> out = new ArrayList<>();
        for (AccountingDayDO row : dayMapper.selectRecent(safe)) {
            out.add(toDTO(row, null, null));
        }
        return out;
    }

    public AccountingDayDTO get(long bizDate) {
        AccountingDayDO row = dayMapper.select(AccountingDayClock.dayStart(bizDate));
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会计日不存在: " + AccountingDayClock.dayText(bizDate));
        }
        return toDTO(row, null, null);
    }

    // ------------------------------------------------------------------ 日切

    /** 日切（全局锁 + CAS + 状态机留痕）；bizDate 为空取当前 OPEN 日。 */
    public AccountingDayDTO runDayCut(Long bizDate, int shardIndex, int shardTotal) {
        long day = bizDate == null ? clock.currentDay() : AccountingDayClock.dayStart(bizDate);
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        // 日切是全局日历动作：锁名不含分片后缀 ⇒ 多实例/多分片只有一个执行者（其余 lockSkipped）
        return dayCutLock.withLock("accountingDayCut:" + day, LOCK_AT_MOST,
                () -> doCut(day, index, total),
                () -> {
                    AccountingDayDTO dto = toDTO(dayMapper.select(day), null, false);
                    dto.setMessage("日切锁未获取（其它实例正在日切），本次跳过");
                    dto.setExecuted(false);
                    return dto;
                });
    }

    private AccountingDayDTO doCut(long day, int shardIndex, int shardTotal) {
        BatchRunDO run = begin(BATCH_DAY_CUT, day, 0, 1);
        try {
            clock.ensureDay(day);
            AccountingDayDO row = dayMapper.select(day);
            if ("CLOSED".equals(row.getStatus())) {
                finish(run, true, "CLOSED", null);
                AccountingDayDTO dto = toDTO(row, null, false);
                dto.setMessage("该会计日已 CLOSED（幂等命中，未重复日切）");
                dto.setContinuous(true);
                return dto;
            }
            if ("OPEN".equals(row.getStatus())) {
                int won = dayMapper.casToCutting(day, System.currentTimeMillis());
                if (won == 0) {
                    row = dayMapper.select(day);
                    if ("CLOSED".equals(row.getStatus())) {
                        finish(run, true, "CLOSED", null);
                        AccountingDayDTO dto = toDTO(row, null, false);
                        dto.setMessage("并发日切已完成（幂等）");
                        dto.setContinuous(true);
                        return dto;
                    }
                    // CUTTING：上一次中断的中间态，继续续跑（各步骤幂等）
                }
            }
            step(run, "SNAPSHOT");
            // 切前快照：Σbalance / ΣD / ΣC / 账户数 / 分录数 / 逐账户余额校验和
            long beforeSum = readYourWrite(() -> nz(balanceMapper.sumBalance()));
            long beforeDebit = readYourWrite(() -> nz(entryMapper.sumDebit()));
            long beforeCredit = readYourWrite(() -> nz(entryMapper.sumCredit()));
            int accounts = readYourWrite(balanceMapper::count);
            int entries = readYourWrite(entryMapper::count);
            String beforeChecksum = dayEndBatchService.balanceChecksum();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("bizDate", day);
            snapshot.put("balanceSum", beforeSum);
            snapshot.put("totalDebit", beforeDebit);
            snapshot.put("totalCredit", beforeCredit);
            snapshot.put("accounts", accounts);
            snapshot.put("entries", entries);
            snapshot.put("balanceChecksum", beforeChecksum);
            snapshot.put("cutAt", System.currentTimeMillis());
            String snapshotJson = TccContextUtil.toJson(snapshot);

            AccountingDayDO snap = dayMapper.select(day);
            snap.setPrevBalanceSnapshot(snapshotJson);
            snap.setBalanceSum(beforeSum);
            snap.setTotalDebit(beforeDebit);
            snap.setTotalCredit(beforeCredit);
            snap.setAccounts(accounts);
            snap.setEntries(entries);
            dayMapper.updateSnapshot(snap);

            step(run, "SUBJECT_SUMMARY");
            dayEndBatchService.aggregate(day, shardIndex, shardTotal);

            step(run, "DAY_END_REPORT");
            DayEndReportDO report = dayEndBatchService.finishReport(day);
            boolean transferred = report != null && Boolean.TRUE.equals(report.getTransferred());

            step(run, "CONTINUITY");
            long afterSum = readYourWrite(() -> nz(balanceMapper.sumBalance()));
            String afterChecksum = dayEndBatchService.balanceChecksum();
            // 日切不动余额：未结转 ⇒ 逐账户校验和必须逐字一致；结转 ⇒ 只允许损益户变动且 Σ 恒 0
            boolean continuous = transferred ? afterSum == beforeSum : afterChecksum.equals(beforeChecksum);
            if (!continuous) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "日切余额连续性校验失败：before=" + beforeSum + " after=" + afterSum);
            }

            step(run, "OPEN_NEXT");
            clock.ensureDay(day + AccountingDayClock.DAY_MS);

            step(run, "CLOSE");
            AccountingDayDO closing = dayMapper.select(day);
            closing.setClosedAt(System.currentTimeMillis());
            closing.setPrevBalanceSnapshot(snapshotJson);
            closing.setBalanceSum(beforeSum);
            closing.setTotalDebit(beforeDebit);
            closing.setTotalCredit(beforeCredit);
            closing.setAccounts(accounts);
            closing.setEntries(entries);
            int closed = dayMapper.casToClosed(closing);
            if (closed == 0) {
                log.warn("AC-3 日切 CAS CLOSED 未命中（并发已完成）bizDate={}", AccountingDayClock.dayText(day));
            }
            finish(run, true, "CLOSED", null);
            AccountingDayDTO dto = toDTO(dayMapper.select(day), afterSum, true);
            dto.setContinuous(true);
            dto.setMessage("日切完成（余额连续，Σbalance=" + afterSum + "）");
            log.info("AC-3 日切完成 bizDate={} shard={}/{} balanceSum={} continuous={} transferred={} nextDay={}",
                    AccountingDayClock.dayText(day), shardIndex, shardTotal, afterSum, true, transferred,
                    AccountingDayClock.dayText(day + AccountingDayClock.DAY_MS));
            return dto;
        } catch (RuntimeException e) {
            finish(run, false, run.getStep(), e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[告警] AC-3 日切失败（状态留痕 FAILED，可重跑续跑）bizDate={} err={}",
                    AccountingDayClock.dayText(day), e.toString());
            throw e;
        }
    }

    // ------------------------------------------------------------------ 日终批

    /** 日终批（逐片锁 + 科目汇总 + 报告）；失败可重跑，幂等。 */
    public DayEndReportDTO runDayEnd(Long bizDate, int shardIndex, int shardTotal) {
        long day = bizDate == null ? clock.currentDay() : AccountingDayClock.dayStart(bizDate);
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        return dayCutLock.withLock("accountingDayEnd:" + day + ":" + index + "/" + total, LOCK_AT_MOST,
                () -> toReportDTO(doDayEnd(day, index, total)),
                () -> {
                    DayEndReportDO existing = dayEndBatchService.report(day);
                    log.info("AC-3 日终批锁未获取（同片其它实例正在跑）bizDate={} shard={}/{}",
                            AccountingDayClock.dayText(day), index, total);
                    return existing == null ? null : toReportDTO(existing);
                });
    }

    private DayEndReportDO doDayEnd(long day, int index, int total) {
        BatchRunDO run = begin(DayEndBatchService.BATCH_DAY_END, day, index, total);
        try {
            clock.ensureDay(day);
            step(run, "SUBJECT_SUMMARY");
            dayEndBatchService.aggregate(day, index, total);
            step(run, "REPORT");
            DayEndReportDO report = dayEndBatchService.finishReport(day);
            boolean ok = report != null && "SUCCESS".equals(report.getStatus());
            finish(run, ok, "DONE", ok ? null : "日终试算不平衡或报告缺失");
            return report;
        } catch (RuntimeException e) {
            finish(run, false, run.getStep(), e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    public DayEndReportDTO getReport(Long bizDate) {
        long day = bizDate == null ? clock.currentDay() : AccountingDayClock.dayStart(bizDate);
        DayEndReportDO row = dayEndBatchService.report(day);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND,
                    "日终报告不存在（请先跑批）: " + AccountingDayClock.dayText(day));
        }
        return toReportDTO(row);
    }

    public List<BatchRunDO> batchRuns(String batchType, Long bizDate, int limit) {
        return batchRunMapper.selectRecent(batchType, bizDate, limit <= 0 ? 20 : Math.min(limit, 200));
    }

    public boolean autoEnabled() {
        return clock.autoCutEnabled();
    }

    // ------------------------------------------------------------------ 批处理状态机留痕

    private BatchRunDO begin(String type, long day, int index, int total) {
        long now = System.currentTimeMillis();
        BatchRunDO run = batchRunMapper.select(type, day, index, total);
        if (run == null) {
            run = new BatchRunDO();
            run.setId("AB" + batchRunMapper.nextId());
            run.setBatchType(type);
            run.setBizDate(day);
            run.setShardIndex(index);
            run.setShardTotal(total);
            run.setAttempt(1);
            run.setStatus("RUNNING");
            run.setStep("START");
            run.setStartedAt(now);
            try {
                batchRunMapper.insert(run);
                return run;
            } catch (DuplicateKeyException e) {
                run = batchRunMapper.select(type, day, index, total);
            }
        }
        run.setAttempt((run.getAttempt() == null ? 0 : run.getAttempt()) + 1);
        run.setStatus("RUNNING");
        run.setStep("START");
        run.setError(null);
        run.setStartedAt(now);
        run.setFinishedAt(null);
        batchRunMapper.update(run);
        return run;
    }

    private void step(BatchRunDO run, String step) {
        run.setStep(step);
        batchRunMapper.update(run);
    }

    private void finish(BatchRunDO run, boolean ok, String step, String error) {
        run.setStatus(ok ? "SUCCESS" : "FAILED");
        run.setStep(step);
        run.setError(error == null ? null : error.substring(0, Math.min(error.length(), 500)));
        run.setFinishedAt(System.currentTimeMillis());
        batchRunMapper.update(run);
    }

    // ------------------------------------------------------------------ 转换

    private static AccountingDayDTO toDTO(AccountingDayDO row, Long afterBalanceSum, Boolean executed) {
        AccountingDayDTO dto = new AccountingDayDTO();
        if (row == null) {
            return dto;
        }
        dto.setBizDate(row.getBizDate());
        dto.setStatus(row.getStatus());
        dto.setOpenedAt(row.getOpenedAt());
        dto.setCutAt(row.getCutAt());
        dto.setClosedAt(row.getClosedAt());
        dto.setPrevBalanceSnapshot(row.getPrevBalanceSnapshot());
        dto.setBalanceSum(row.getBalanceSum());
        dto.setTotalDebit(row.getTotalDebit());
        dto.setTotalCredit(row.getTotalCredit());
        dto.setAccounts(row.getAccounts());
        dto.setEntries(row.getEntries());
        dto.setAfterBalanceSum(afterBalanceSum);
        dto.setExecuted(executed);
        return dto;
    }

    static DayEndReportDTO toReportDTO(DayEndReportDO row) {
        if (row == null) {
            return null;
        }
        DayEndReportDTO dto = new DayEndReportDTO();
        dto.setBizDate(row.getBizDate());
        dto.setIncomeTotal(row.getIncomeTotal());
        dto.setExpenseTotal(row.getExpenseTotal());
        dto.setProfit(row.getProfit());
        dto.setTransferred(row.getTransferred());
        dto.setTransferBizNo(row.getTransferBizNo());
        dto.setDebitTotal(row.getDebitTotal());
        dto.setCreditTotal(row.getCreditTotal());
        dto.setBalanced(AccountingDayClock.nz(row.getDebitTotal()) == AccountingDayClock.nz(row.getCreditTotal()));
        dto.setBalanceSum(row.getBalanceSum());
        dto.setSubjectCount(row.getSubjectCount());
        dto.setEntryCount(row.getEntryCount());
        dto.setLegacyEntryCount(row.getLegacyEntryCount());
        dto.setStatus(row.getStatus());
        dto.setUpdatedAt(row.getUpdatedAt());
        return dto;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }
}