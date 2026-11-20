package com.qoder.payment.payment.service;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

/**
 * I-3 支付域作业实现（JobHandler SPI）：调度触发与业务执行解耦。
 *
 * <p>三个作业名与 XXL-Job JobHandler 名一致：{@code recoverTransactions} / {@code dispatchOutbox} /
 * {@code reconcileProcessing}。触发来源三条（本地 @Scheduled、XXL-Job、/actuator/ppjob 手动），
 * 都经 {@code JobHandlerRegistry} 持有的 Spring 代理调用，因此 ShedLock 一致生效。
 *
 * <p>锁名不嵌套（ShedLock 非重入）：{@code dispatchOutbox} 的锁只加在
 * {@link OutboxDispatcher#dispatchPending()} 上，本处只委托，避免同名锁自锁导致投递停摆。
 *
 * <p>分片：支付域补偿作业为全局扫描（dtx/outbox 按到期时间取批），不做分片切分；
 * 分片广播语义由对账 {@code reconRun} 承担（RC-5）。多实例互斥由 ShedLock 保证（防 E-6 重复驱动）。
 */
public final class PaymentJobHandlers {

    private PaymentJobHandlers() {
    }

    /** TCC 二阶段恢复：锁加在作业入口（协调器 recover() 属 tcc-core 组件，不在本卡改动范围）。 */
    @Component
    @RequiredArgsConstructor
    public static class RecoverTransactionsJob implements JobHandler {

        private final TccCoordinator coordinator;

        @Override
        public String name() {
            return "recoverTransactions";
        }

        @Override
        @SchedulerLock(name = "recoverTransactions", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
        public void execute(int shardIndex, int shardTotal, String param) {
            coordinator.recover();
        }
    }

    /** outbox 可靠投递兜底：锁在 OutboxDispatcher#dispatchPending（本处只委托，不重复加锁）。 */
    @Component
    @RequiredArgsConstructor
    public static class DispatchOutboxJob implements JobHandler {

        private final OutboxDispatcher outboxDispatcher;

        @Override
        public String name() {
            return "dispatchOutbox";
        }

        @Override
        public void execute(int shardIndex, int shardTotal, String param) {
            outboxDispatcher.dispatchPending();
        }
    }

    /** PROCESSING 查询补单收敛（CH-1/S-5/P-1）：重复扫描无副作用，仍加锁避免多实例重复驱动。 */
    @Component
    @RequiredArgsConstructor
    public static class ReconcileProcessingJob implements JobHandler {

        private final PaymentReconcileJob reconcileJob;

        @Override
        public String name() {
            return "reconcileProcessing";
        }

        @Override
        @SchedulerLock(name = "reconcileProcessing", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
        public void execute(int shardIndex, int shardTotal, String param) {
            reconcileJob.reconcileProcessing();
        }
    }

    /**
     * P2-E P-7 营销抵扣回退兜底：扫 {@code marketing_status='ROLLBACK_PENDING'} 的支付单，
     * 重试「退回已核销权益 + 资金反向 + 目标维度重分摊」直到收敛（服务端最终一致，不依赖前端补偿）。
     *
     * <p>幂等：营销域退回接口本身幂等（(COUPON_REFUND,useId)/(POINTS_REFUND,origBizNo)/
     * (ACTIVITY_REFUND,useId) + 状态 CAS），重复驱动不重复反向动账；ShedLock 保证多实例互斥。
     * 营销域未启用时作业空转（{@code enabled=false} 直接返回 0），绝不报错、不影响其它作业。
     */
    @Component
    @RequiredArgsConstructor
    public static class MarketingRollbackJob implements JobHandler {

        private final MarketingPayService marketingPayService;

        @Override
        public String name() {
            return "marketingRollback";
        }

        @Override
        @SchedulerLock(name = "marketingRollback", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
        public void execute(int shardIndex, int shardTotal, String param) {
            marketingPayService.sweepRollbackPending(50);
        }
    }
}