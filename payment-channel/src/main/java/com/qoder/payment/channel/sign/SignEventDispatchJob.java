package com.qoder.payment.channel.sign;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.common.job.LocalScheduledRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * CH-6 签约事件事务 outbox 投递作业（JobHandler 名 {@code signEventDispatch}）。
 *
 * <p>SIGN_SUCCESS/SIGN_CANCEL 与协议状态变更同一本地事务落 sign_event，提交后即投一次；
 * notify 域不可达/失败 → 本作业按退避重试（服务端兜底，绝不丢事件）。
 * notify 域 {@code (NOTIFY, event:bizId)} 幂等吸收重复投递。
 *
 * <p>触发：local {@code @Scheduled} 兜底 / docker XXL-Job / {@code POST /actuator/ppjob/signEventDispatch}。
 * ShedLock 锁名 {@code signEventDispatch} 多实例互斥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignEventDispatchJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final SignEventPublisher publisher;
    private final DynamicConfig config;
    private final ObjectProvider<LocalScheduledRunner> localRunner;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "signEventDispatch";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        if (!config.getBool(SignConfigKeys.ENABLED, SignConfigKeys.DEFAULT_ENABLED)) {
            return;
        }
        int limit = config.getInt(SignConfigKeys.RETRY_BATCH_SIZE, SignConfigKeys.DEFAULT_RETRY_BATCH_SIZE);
        if (param != null && !param.isBlank()) {
            try {
                limit = Integer.parseInt(param.trim());
            } catch (NumberFormatException ignored) {
                // 非法参数按默认批量
            }
        }
        int batch = limit;
        runLocked(() -> {
            int sent = publisher.dispatchDue(batch);
            if (sent > 0) {
                log.info("CH-6 签约事件投递作业完成 sent={} shard={}/{}", sent, shardIndex, shardTotal);
            }
        });
    }

    @Scheduled(fixedDelay = 10000)
    public void scheduled() {
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner == null) {
            execute(0, 1, null);
            return;
        }
        runner.runIfLocal(name());
    }

    private void runLocked(Runnable action) {
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            action.run();
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), name(), LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock(action, lock);
    }
}