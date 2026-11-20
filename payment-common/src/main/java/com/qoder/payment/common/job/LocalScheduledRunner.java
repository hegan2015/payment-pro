package com.qoder.payment.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I-3 本地调度兜底（{@code payment.job.local.enabled} 未设置即装配，local 零依赖不连 XXL-Job）。
 *
 * <p><b>包装现有 @Scheduled，不改其语义</b>：各域原有 {@code @Scheduled(fixedDelay=5000/3000)} 注解、
 * 线程模型、延迟值逐字节保留，方法体改为调用 {@link #runIfLocal(String)}；local profile 下
 * {@code xxlEnabled=false} → 闸门恒开 → 与改造前完全一致（同线程、同 fixedDelay、同异常处理）。
 *
 * <p><b>降级红线</b>：docker 接入 XXL-Job 后，本地兜底进入「静默待命」——仅当某作业确实被调度中心
 * 持续驱动时才让位；一旦执行器未注册 / 调度中心不可达 / 该作业超过 {@code stallThresholdMs}
 * 未收到触发，立即自动回切本地 @Scheduled。资金补偿与对账的服务端兜底常驻，绝不因调度中心宕机停摆。
 * 回切窗口内两条路径可能同时触发，由 ShedLock + 既有幂等模板吸收（不会重复驱动）。
 */
public class LocalScheduledRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalScheduledRunner.class);

    private final JobSettings settings;
    private final JobRuntimeState state;
    private final JobHandlerRegistry registry;

    public LocalScheduledRunner(JobSettings settings, JobRuntimeState state, JobHandlerRegistry registry) {
        this.settings = settings;
        this.state = state;
        this.registry = registry;
        log.info("I-3 本地调度兜底装配 standbyWhenXxlHealthy={} stallThresholdMs={} xxlEnabled={}",
                settings.localStandby(), settings.stallThresholdMs(), settings.xxlEnabled());
    }

    /**
     * 该作业此刻是否应由本地 @Scheduled 驱动。
     * 逐作业判定：调度中心只接管它真正在触发的作业，未配置任务的作业仍由本地兜底常驻驱动。
     */
    public boolean isLocalActive(String jobName) {
        if (!settings.xxlEnabled() || !state.xxlEnabled()) {
            return true;
        }
        if (!settings.localStandby()) {
            return true;
        }
        if (!state.xxlHealthy()) {
            return true;
        }
        Long last = state.lastRemoteTriggerAt(jobName);
        return last == null || System.currentTimeMillis() - last > settings.stallThresholdMs();
    }

    /** 供原有 @Scheduled 方法体调用：闸门开则执行作业（单片全量），关则静默跳过。 */
    public void runIfLocal(String jobName) {
        if (!isLocalActive(jobName)) {
            state.markLocalSuppressed(jobName);
            return;
        }
        state.markLocalRun(jobName);
        registry.invoke(jobName, 0, 1, null, "LOCAL");
    }

    public JobSettings settings() {
        return settings;
    }
}