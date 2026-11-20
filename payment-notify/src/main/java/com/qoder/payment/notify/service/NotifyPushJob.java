package com.qoder.payment.notify.service;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.common.job.LocalScheduledRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * I-3 通知推送作业（JobHandler 名 {@code notifyPush}）。
 *
 * <p>调度触发与业务执行解耦：本类只负责「触发」，业务与互斥在 {@link NotifyPusher#push()}
 * （{@code @SchedulerLock(name="notifyPush")}）——多实例只有一个执行，商户不会被重复推送。
 *
 * <p>local 语义逐字节不变：原 {@code NotifyPusher#push} 上的 {@code @Scheduled(fixedDelay = 3000)}
 * 原样迁到本触发方法（同延迟、同 Spring 调度线程池），方法体经 {@link LocalScheduledRunner} 闸门；
 * local profile 闸门恒开，docker 由 XXL-Job 驱动时静默待命、调度中心失联自动回切。
 *
 * <p>死信兜底扫描 {@code NotifyPusher#deadSweep} 不属作业，保持原 @Scheduled 常驻不变。
 */
@Component
@RequiredArgsConstructor
public class NotifyPushJob implements JobHandler {

    private final NotifyPusher notifyPusher;
    private final ObjectProvider<LocalScheduledRunner> localRunner;

    @Override
    public String name() {
        return "notifyPush";
    }

    /** 原本地调度入口（fixedDelay 3000ms 不变）。 */
    @Scheduled(fixedDelay = 3000)
    public void scheduledPush() {
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner != null) {
            runner.runIfLocal(name());
        }
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        notifyPusher.push();
    }
}