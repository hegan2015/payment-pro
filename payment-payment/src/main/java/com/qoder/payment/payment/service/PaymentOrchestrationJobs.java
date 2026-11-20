package com.qoder.payment.payment.service;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.common.job.LocalScheduledRunner;
import com.qoder.payment.payment.dataobject.ShareOrderDO;
import com.qoder.payment.payment.mapper.ShareOrderMapper;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1-I 编排作业调度（additive，独立于既有 PaymentScheduler，既有三作业节拍一字未改）：
 * <ul>
 *   <li>{@code reconcileRefunds}：P-4 退款 PROCESSING 补单收敛（5s，复用 ReconcilePolicy）；</li>
 *   <li>{@code shareExecute}：P-3 分账执行兜底（5s）——到期 DELAYED/卡住 PENDING 单直驱
 *       + BACKING 单回退续驱（outbox DEAD 后的服务端兜底，同一幂等键不重复动账）。</li>
 * </ul>
 *
 * <p>双 profile：local @Scheduled 零依赖；docker XXL-Job 驱动时本地静默让位、
 * 调度中心失速自动回切（LocalScheduledRunner 闸门）。多实例 ShedLock 互斥。
 */
public final class PaymentOrchestrationJobs {

    private PaymentOrchestrationJobs() {
    }

    @Component
    @RequiredArgsConstructor
    public static class Scheduler {

        private final ObjectProvider<LocalScheduledRunner> localRunner;

        @Scheduled(fixedDelay = 5000)
        public void reconcileRefunds() {
            runLocal("reconcileRefunds");
        }

        @Scheduled(fixedDelay = 5000)
        public void shareExecute() {
            runLocal("shareExecute");
        }

        private void runLocal(String jobName) {
            LocalScheduledRunner runner = localRunner.getIfAvailable();
            if (runner != null) {
                runner.runIfLocal(jobName);
            }
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class ReconcileRefundsJob implements JobHandler {

        private final RefundReconcileJob refundReconcileJob;

        @Override
        public String name() {
            return "reconcileRefunds";
        }

        @Override
        @SchedulerLock(name = "reconcileRefunds", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
        public void execute(int shardIndex, int shardTotal, String param) {
            refundReconcileJob.reconcileProcessing();
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class ShareExecuteJob implements JobHandler {

        private final ShareOrderMapper shareOrderMapper;
        private final ProfitShareService shareService;

        @Override
        public String name() {
            return "shareExecute";
        }

        @Override
        @SchedulerLock(name = "shareExecute", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
        public void execute(int shardIndex, int shardTotal, String param) {
            long now = System.currentTimeMillis();
            List<ShareOrderDO> due = shareOrderMapper.selectDuePending(now, 50);
            for (ShareOrderDO order : due) {
                try {
                    shareService.execute(order.getId());
                } catch (Exception e) {
                    // 单笔异常不影响其余（逐笔隔离）
                }
            }
            for (ShareOrderDO order : shareOrderMapper.selectBacking(50)) {
                try {
                    shareService.driveBack(order);
                } catch (Exception e) {
                    // 逐笔隔离
                }
            }
        }
    }
}