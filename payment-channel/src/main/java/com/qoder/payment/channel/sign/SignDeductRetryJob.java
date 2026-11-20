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
 * CH-6 代扣重试/收敛作业（JobHandler 名 {@code signDeductRetry}）。
 *
 * <p><b>不新增进程、不占用 XXL 执行器端口</b>（9994-9998 已满）：复用 channel 域既有执行器配置，
 * local 由 {@code @Scheduled} + ShedLock 兜底常驻，docker 由 XXL-Job/运维端点
 * {@code POST /actuator/ppjob/signDeductRetry}（channel 内网面 18087）触发；
 * 调度中心未驱动本作业时本地兜底自动回切（资金一致性服务端常驻）。
 *
 * <p>处理内容：① 在途扣款单补单收敛（按支付单真实终态，绝不臆断）② 到期 FAILED 重试
 * ③ 重试耗尽 TERMINATED ④ 协议到期 EXPIRED。ShedLock 锁名 {@code signDeductRetry}
 * 保证多实例/多分片互斥（单片全量扫描，不重不漏）。
 *
 * <p><b>ride 零影响</b>：只读写 sign_* 新表，ride 无签约 ⇒ 扫描空集立即返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignDeductRetryJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    private final SignDeductService deductService;
    private final DynamicConfig config;
    private final ObjectProvider<LocalScheduledRunner> localRunner;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "signDeductRetry";
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
                // 非法参数按默认批量，不抛（调度参数容错）
            }
        }
        int batch = limit;
        // 三条触发路径（本地 @Scheduled / XXL-Job / 运维端点）统一在此加锁，多实例互斥不重不漏
        runLocked(() -> {
            int handled = deductService.runRetry(batch);
            if (handled > 0) {
                log.info("CH-6 代扣重试/收敛作业完成 handled={} shard={}/{}", handled, shardIndex, shardTotal);
            }
        });
    }

    /** local 兜底周期调度（docker XXL 正常驱动本作业时自动静默）。 */
    @Scheduled(fixedDelay = 15000)
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