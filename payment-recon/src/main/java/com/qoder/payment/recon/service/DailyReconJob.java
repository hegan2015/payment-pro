package com.qoder.payment.recon.service;

import com.qoder.payment.common.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RC-2/RC-5 日切批量对账作业（JobHandler 名 {@code reconDaily}，XXL-Job 分片广播）。
 *
 * <p>逐片加锁：锁名 {@code reconDaily:<bizDate>:<shardIndex>/<shardTotal>}，
 * 各片可并行、同片多实例互斥（编程式 ShedLock，注解锁名无法按分片/会计日区分）。
 * 单片失败不影响其余片；失败片重跑幂等（批次行复用 + 差错单去重）。
 *
 * <p>param：会计日 epoch millis 或 yyyyMMdd（可空 = accounting 当前账面会计日）。
 *
 * <p><b>默认不自动跑批</b>：无 @Scheduled 周期触发，由 XXL-Job（任务记录 trigger_status=0 建好待启用）/
 * 控制台 / {@code POST /actuator/ppjob/reconDaily} 触发，与 reconRun/accountingDayCut 先例一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReconJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(15);

    private final DailyReconService dailyReconService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "reconDaily";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        long day = dailyReconService.resolveDay(DailyReconService.parseDayText(param));
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，reconDaily 直接执行 bizDate={} shard={}/{}", day, index, total);
            dailyReconService.runDailySharded(day, index, total);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + day + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> dailyReconService.runDailySharded(day, index, total), config);
    }
}