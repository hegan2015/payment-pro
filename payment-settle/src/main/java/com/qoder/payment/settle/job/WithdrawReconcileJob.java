package com.qoder.payment.settle.job;

import com.qoder.payment.common.db.ShardKeys;
import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.facade.settle.WithdrawOrderDTO;
import com.qoder.payment.settle.service.WithdrawService;
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
import java.util.List;

/**
 * ST-4 出金补单作业（JobHandler 名 {@code withdrawReconcile}）：扫 PROCESSING 提现单，
 * 查渠道/沙箱终态收敛（回执丢失兜底）。按 accountId 取模分片 + 逐片 ShedLock；
 * 终态 CAS + 账务 bizNo 幂等 ⇒ 多实例/重复扫描不重扣不重退。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawReconcileJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final WithdrawService withdrawService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "withdrawReconcile";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            run(index, total);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> run(index, total), config);
    }

    private void run(int index, int total) {
        List<WithdrawOrderDTO> pending = withdrawService.list("PROCESSING");
        int converged = 0;
        for (WithdrawOrderDTO order : pending) {
            if (ShardKeys.shardOf(order.getAccountId(), total) != index) {
                continue;
            }
            WithdrawOrderDTO after = withdrawService.reconcile(order.getId());
            if (!"PROCESSING".equals(after.getStatus())) {
                converged++;
            }
        }
        log.info("ST-4 出金补单 shard={}/{} pending={} converged={}", index, total, pending.size(), converged);
    }
}