package com.qoder.payment.payment.service;

import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 异步补偿双调度：TCC 二阶段恢复 + outbox 可靠投递。 */
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final TccCoordinator coordinator;
    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelay = 5000)
    public void recoverTransactions() {
        coordinator.recover();
    }

    @Scheduled(fixedDelay = 3000)
    public void dispatchOutbox() {
        outboxDispatcher.dispatchPending();
    }
}