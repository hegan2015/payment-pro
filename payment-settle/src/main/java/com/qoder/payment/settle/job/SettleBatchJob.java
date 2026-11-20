package com.qoder.payment.settle.job;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.settle.service.SettleBatchService;
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
 * ST-1 日切结算跑批作业（JobHandler 名 {@code settleBatch}）。
 *
 * <p>逐片加锁 {@code settleBatch:<shardIndex>/<shardTotal>}：各片可并行、<b>同片多实例互斥</b>
 * （ShedLock 锁表与业务同库，多实例共享同一 MySQL 即天然互斥）。用编程式 API 而非
 * {@code @SchedulerLock}，因为注解锁名静态、无法按分片区分（共用锁名会互相跳过导致漏结）。
 *
 * <p>param：业务日 epoch millis（可空=窗口内全部到期日）；{@code force} 前缀表示忽略 T+N 期限补跑。
 * 例：{@code "1795104000000"} / {@code "force:1795104000000"} / {@code "force"}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettleBatchJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    private final SettleBatchService batchService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "settleBatch";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        String raw = param == null ? "" : param.trim();
        boolean force = raw.startsWith("force");
        String datePart = force ? raw.substring(Math.min(5, raw.length())).replaceFirst("^:", "") : raw;
        Long bizDate = null;
        if (!datePart.isBlank()) {
            try {
                bizDate = Long.parseLong(datePart.trim());
            } catch (NumberFormatException e) {
                log.warn("ST-1 跑批参数非法（忽略 bizDate）param={}", param);
            }
        }
        final Long day = bizDate;
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，settleBatch 直接执行 shard={}/{}（幂等键吸收重复）", index, total);
            batchService.run(day, index, total, force);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> batchService.run(day, index, total, force), config);
    }
}