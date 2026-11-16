package com.qoder.payment.payment.service;

import com.qoder.payment.dtx.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.payment.dataobject.OutboxDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 本地消息表可靠投递：至少一次语义 + 接收端幂等 = 恰好一次效果。
 * 指数退避重试，耗尽置 DEAD 等待对账/人工介入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int[] BACKOFF_SECONDS = {3, 6, 12, 24, 48, 96, 192, 384};
    private static final int MAX_ATTEMPTS = 8;

    private final OutboxMapper outboxMapper;

    @DubboReference
    private SettleFacade settleFacade;

    @DubboReference
    private NotifyFacade notifyFacade;

    public void dispatchPending() {
        List<OutboxDO> pending = outboxMapper.selectPending(System.currentTimeMillis(), 50);
        for (OutboxDO message : pending) {
            try {
                deliver(message);
                outboxMapper.markDelivered(message.getId());
            } catch (Exception e) {
                int attempts = message.getAttempts() + 1;
                String error = e.getMessage();
                if (attempts >= MAX_ATTEMPTS) {
                    outboxMapper.markDead(message.getId(), error);
                    log.error("outbox 投递重试耗尽置 DEAD id={} event={}: {}", message.getId(), message.getEvent(), error);
                } else {
                    long delay = BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
                    outboxMapper.markRetry(message.getId(), attempts, System.currentTimeMillis() + delay, error);
                    log.warn("outbox 投递待重试 id={} event={} attempts={}: {}", message.getId(), message.getEvent(), attempts, error);
                }
            }
        }
    }

    private void deliver(OutboxDO message) {
        String payload = message.getPayload();
        switch (message.getEvent()) {
            case "SETTLE_PAY" -> settleFacade.recordPay(TccContextUtil.fromJson(payload, SettlePayRequest.class)).orThrow();
            case "SETTLE_REFUND" -> settleFacade.recordRefund(TccContextUtil.fromJson(payload, SettleRefundRequest.class)).orThrow();
            case "NOTIFY_PAY", "NOTIFY_REFUND" -> notifyFacade.send(TccContextUtil.fromJson(payload, NotifyRequest.class)).orThrow();
            default -> throw new IllegalStateException("未知 outbox 事件: " + message.getEvent());
        }
    }
}