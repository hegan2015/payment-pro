package com.qoder.payment.trade.job;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.trade.service.OrderExpireService;
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
 * T-4 未支付订单超时关闭作业（JobHandler 名 {@code tradeOrderExpire}）。
 *
 * <p>逐片加锁 {@code tradeOrderExpire:<idx>/<total>}：各片可并行、<b>同片多实例互斥</b>
 * （ShedLock 锁表与业务同库 pp_trade，多实例共享同一 MySQL 即天然互斥）。
 * 用编程式 API 而非 {@code @SchedulerLock}：注解锁名静态、无法按分片区分（共用锁名会互相跳过导致漏关）。
 *
 * <p>触发面：XXL-Job（docker 执行器）/ 控制台 {@code POST /api/trade/expire/run} /
 * {@code POST /actuator/ppjob/tradeOrderExpire}。<b>本地 @Scheduled 自动扫描默认关闭</b>
 * （{@code trade.expire.auto.enabled=false}），绝不自动跑批干扰既有断言。
 *
 * <p>param：{@code preview} 只预览到期候选不关闭；其余忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpireJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final OrderExpireService expireService;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "tradeOrderExpire";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        if (param != null && param.trim().equalsIgnoreCase("preview")) {
            log.info("T-4 到期预览 shard={}/{} 候选={}", index, total, expireService.due(index, total, 200).size());
            return;
        }
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            log.warn("ShedLock 未装配，tradeOrderExpire 直接执行 shard={}/{}（CAS+留痕唯一键吸收重复）", index, total);
            expireService.closeExpired(index, total);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> expireService.closeExpired(index, total), config);
    }
}