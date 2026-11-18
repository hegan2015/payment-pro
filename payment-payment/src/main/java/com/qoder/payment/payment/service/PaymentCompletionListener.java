package com.qoder.payment.payment.service;

import com.qoder.tcc.TccCompletionListener;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.payment.payment.mq.MqProperties;
import com.qoder.payment.payment.mq.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TCC 终态回调：与全局事务终态落库同一本地事务，
 * 支付单/退款单状态翻转与 outbox 消息写入原子提交（事务性 outbox）。
 * MQ 开启时事务提交后经 RocketMQ 主投递，outbox 行留宽限期由轮询兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletionListener implements TccCompletionListener {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OutboxMapper outboxMapper;
    private final MqProperties mqProps;
    private final ObjectProvider<OutboxPublisher> publisherProvider;

    @Override
    public void onConfirmed(String xid) {
        long now = System.currentTimeMillis();
        long nextRetryAt = mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L;
        OutboxPublisher publisher = mqProps.isEnabled() ? publisherProvider.getIfAvailable() : null;
        List<Runnable> publishes = new ArrayList<>();

        PaymentDO payment = paymentMapper.selectByXid(xid);
        if (payment != null && "PROCESSING".equals(payment.getStatus())) {
            paymentMapper.finish(payment.getId(), "SUCCESS", null);

            SettlePayRequest settle = new SettlePayRequest();
            settle.setPaymentId(payment.getId());
            settle.setOrderId(payment.getOrderId());
            settle.setMerchantId(payment.getMerchantId());
            settle.setAmount(payment.getAmount());
            settle.setFee(payment.getFee());
            settle.setMethod(payment.getMethod());
            String settlePayload = TccContextUtil.toJson(settle);
            outboxMapper.insert("SETTLE_PAY", payment.getId(), settlePayload, nextRetryAt, now);
            capture(publishes, publisher, "SETTLE_PAY", payment.getId(), settlePayload);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", payment.getId());
            payload.put("orderId", payment.getOrderId());
            payload.put("amount", payment.getAmount());
            payload.put("status", "SUCCESS");
            String notifyPayload = TccContextUtil.toJson(notify("PAY_SUCCESS", payment.getId(), payload, payment.getCallbackUrl()));
            outboxMapper.insert("NOTIFY_PAY", payment.getId(), notifyPayload, nextRetryAt, now);
            capture(publishes, publisher, "NOTIFY_PAY", payment.getId(), notifyPayload);
            log.info("支付单提交成功 paymentId={} xid={}", payment.getId(), xid);
        }
        RefundDO refund = refundMapper.selectByXid(xid);
        if (refund != null && "PROCESSING".equals(refund.getStatus())) {
            refundMapper.finish(refund.getId(), "SUCCESS");
            PaymentDO pay = paymentMapper.selectById(refund.getPaymentId());
            paymentMapper.updateRefunded(pay.getId(),
                    pay.getRefundedAmount() + refund.getAmount(),
                    pay.getRefundedFee() + refund.getFeeRefund());

            SettleRefundRequest settle = new SettleRefundRequest();
            settle.setBizId(refund.getId());
            settle.setPaymentId(refund.getPaymentId());
            settle.setAmount(refund.getAmount());
            settle.setFeeRefund(refund.getFeeRefund());
            String settlePayload = TccContextUtil.toJson(settle);
            outboxMapper.insert("SETTLE_REFUND", refund.getId(), settlePayload, nextRetryAt, now);
            capture(publishes, publisher, "SETTLE_REFUND", refund.getId(), settlePayload);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", refund.getPaymentId());
            payload.put("refundId", refund.getId());
            payload.put("amount", refund.getAmount());
            String notifyPayload = TccContextUtil.toJson(notify("REFUND_SUCCESS", refund.getId(), payload, pay.getCallbackUrl()));
            outboxMapper.insert("NOTIFY_REFUND", refund.getId(), notifyPayload, nextRetryAt, now);
            capture(publishes, publisher, "NOTIFY_REFUND", refund.getId(), notifyPayload);
            log.info("退款单提交成功 refundId={} xid={}", refund.getId(), xid);
        }

        if (!publishes.isEmpty()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishes.forEach(Runnable::run);
                    }
                });
            } else {
                publishes.forEach(Runnable::run);
            }
        }
    }

    @Override
    public void onCancelled(String xid, String reason) {
        PaymentDO payment = paymentMapper.selectByXid(xid);
        if (payment != null && "PROCESSING".equals(payment.getStatus())) {
            paymentMapper.finish(payment.getId(), "FAILED", reason);
            log.info("支付单回滚失败 paymentId={} xid={} reason={}", payment.getId(), xid, reason);
        }
        RefundDO refund = refundMapper.selectByXid(xid);
        if (refund != null && "PROCESSING".equals(refund.getStatus())) {
            refundMapper.finish(refund.getId(), "FAILED");
            log.info("退款单回滚失败 refundId={} xid={} reason={}", refund.getId(), xid, reason);
        }
    }

    private static void capture(List<Runnable> publishes, OutboxPublisher publisher,
                                String event, String bizId, String payload) {
        if (publisher != null) {
            publishes.add(() -> publisher.publish(event, bizId, payload));
        }
    }

    private static NotifyRequest notify(String event, String bizId, Map<String, Object> payload, String callbackUrl) {
        NotifyRequest request = new NotifyRequest();
        request.setEvent(event);
        request.setBizId(bizId);
        request.setPayload(payload);
        request.setCallbackUrl(callbackUrl);
        return request;
    }
}