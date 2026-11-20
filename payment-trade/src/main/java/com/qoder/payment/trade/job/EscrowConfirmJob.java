package com.qoder.payment.trade.job;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.trade.service.EscrowService;
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
 * T-1 担保超时自动确认作业（JobHandler 名 {@code escrowAutoConfirm}）。
 *
 * <p>逐片 ShedLock {@code escrowAutoConfirm:<idx>/<total>}（同片多实例互斥、各片并行），
 * 分片按 orderId CRC32 取模 ⇒ 不重不漏；逐单 (ESCROW_CONFIRM,orderId) 幂等 + escrow_status CAS，
 * 重复触发/宕机重跑绝不重复打款。
 *
 * <p>本地 @Scheduled 自动确认默认关闭（{@code trade.escrow.auto.enabled=false}），
 * 只由 XXL-Job / 控制台 {@code POST /api/trade/escrow/auto-confirm} / {@code /actuator/ppjob} 触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowConfirmJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final EscrowService escrowService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "escrowAutoConfirm";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，escrowAutoConfirm 直接执行 shard={}/{}（幂等键+CAS 吸收重复）", index, total);
            escrowService.autoConfirmDue(index, total);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> escrowService.autoConfirmDue(index, total), config);
    }
}