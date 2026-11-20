package com.qoder.payment.payment.service;

import com.qoder.payment.common.job.LocalScheduledRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步补偿三调度：TCC 二阶段恢复 + outbox 可靠投递 + PROCESSING 查询补单收敛（CH-1/S-5/P-1）。
 *
 * <p>I-3 改造：<b>本类只保留「触发」职责，@Scheduled 注解与 fixedDelay 值逐字节不变</b>
 * （5000 / 3000 / ${payment.reconcile.interval-ms:5000}，同 Spring 调度线程池、同异常语义）。
 * 业务执行与分布式互斥下沉到 {@link PaymentJobHandlers}（JobHandler SPI + ShedLock），
 * 由 {@link LocalScheduledRunner} 闸门决定本地兜底是否驱动：
 * <ul>
 *   <li>local：xxl 未启用 → 闸门恒开 → 与改造前完全一致（零依赖，不连 XXL-Job）；</li>
 *   <li>docker：XXL-Job 持续驱动该作业时本地静默让位；调度中心不可达/未配置该作业/超 10s 未触发
 *       → 自动回切本地兜底（资金一致性服务端常驻，绝不因调度中心宕机停摆）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final ObjectProvider<LocalScheduledRunner> localRunner;

    @Scheduled(fixedDelay = 5000)
    public void recoverTransactions() {
        runLocal("recoverTransactions");
    }

    @Scheduled(fixedDelay = 3000)
    public void dispatchOutbox() {
        runLocal("dispatchOutbox");
    }

    /** 在途支付单兜底收敛：阈值/退避/批量见 ReconcilePolicy（默认 60s 超时、5s 扫描）。 */
    @Scheduled(fixedDelayString = "${payment.reconcile.interval-ms:5000}")
    public void reconcileProcessing() {
        runLocal("reconcileProcessing");
    }

    /**
     * P2-E P-7 营销抵扣回退兜底（additive，独立周期，不改上方三个既有 @Scheduled 的注解与 fixedDelay）。
     * 无 ROLLBACK_PENDING 单时空转（一次索引扫描），营销域未启用时直接返回，零影响。
     */
    @Scheduled(fixedDelayString = "${payment.marketing.rollback-interval-ms:15000}")
    public void marketingRollback() {
        runLocal("marketingRollback");
    }

    private void runLocal(String jobName) {
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        if (runner != null) {
            runner.runIfLocal(jobName);
        }
    }
}