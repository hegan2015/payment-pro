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
 * I-3 分片对账作业（JobHandler 名 {@code reconRun}，XXL-Job 分片广播 → RC-5 日切对账铺垫）。
 *
 * <p>逐片加锁：锁名 {@code reconRun:<shardIndex>/<shardTotal>}，各片可并行、同片多实例互斥。
 * 这里用 ShedLock 编程式 API（{@link LockingTaskExecutor}）而非 {@code @SchedulerLock}，
 * 因为注解锁名是静态的，无法按分片区分（若共用一个锁名，分片广播会互相跳过导致漏核）。
 *
 * <p><b>不新增本地周期调度</b>：recon 域原本就没有周期性全量对账（只有差错自动修复 60s 扫描），
 * 保持现状 —— 对账由控制台 /api/recon/run、XXL-Job 任务或运维端点触发，避免凭空增加报告/差错单。
 * 控制台 {@code ReconFacade.run()} 仍走无锁全量 {@code ReconService.run()}，语义不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconRunJob implements JobHandler {

    /** 单片最长持有：全量对账逐笔回查五域，留足余量；进程猝死时锁自动到期。 */
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(15);

    private final ReconService reconService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "reconRun";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            // 锁未装配（payment.job.shedlock.enabled=false）：退化为直接执行，仍由既有幂等吸收重复
            log.warn("ShedLock 未装配，reconRun 直接执行 shard={}/{}", index, total);
            reconService.runSharded(index, total);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        // 显式 Runnable 重载（ShedLock 5.x Task/TaskWithResult 重载声明 throws Throwable，与 SPI throws Exception 不兼容）
        executor.executeWithLock((Runnable) () -> reconService.runSharded(index, total), config);
    }
}