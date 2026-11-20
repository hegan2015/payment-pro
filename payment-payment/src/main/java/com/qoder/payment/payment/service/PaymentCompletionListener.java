package com.qoder.payment.payment.service;

import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.tcc.TccCompletionListener;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.payment.dataobject.LegAllocDO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import com.qoder.payment.payment.dataobject.RefundDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import com.qoder.payment.payment.mapper.PaymentLegMapper;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.payment.payment.mq.MqProperties;
import com.qoder.payment.payment.mq.OutboxPublisher;
import com.qoder.payment.payment.mq.TopicRouter;
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
 *
 * <p>I-4：outbox 写入时按 {@link TopicRouter} 落路由 topic（additive 元数据）；
 * 半消息发送在 afterCommit 由 publisher 完成（事务消息模式先发半消息，再按本事务已落库的
 * outbox 行判定 commit/rollback，崩溃窗口由 broker 回查兜底）。本地事务时序、grace、
 * 幂等键与 BALANCE 同步语义逐字节不变；outbox 仍是唯一事实来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletionListener implements TccCompletionListener {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OutboxMapper outboxMapper;
    /** P-2 组合腿终态/已退累加（additive：仅 COMBO 单生效，单腿路径逐字节不变）。 */
    private final PaymentLegMapper legMapper;
    private final MqProperties mqProps;
    private final TopicRouter topicRouter;
    private final ObjectProvider<OutboxPublisher> publisherProvider;
    /** I-5b：分片态把终态整行真值双写到影子侧（未装配分片时为 null，零影响）。 */
    private final ObjectProvider<ShardRowMirror> shardRowMirror;
    /** P2-E P-7：TCC 回滚终态须退回已核销营销权益（无营销诉求的单零影响、零 RPC）。 */
    private final MarketingPayService marketingPayService;

    /** TCC 回滚后重开合并单用（PAYING→CREATED）；CAS 幂等，失败由下次 payMerge 的 PAYING 续驱兜底。 */
    @org.apache.dubbo.config.annotation.DubboReference
    private com.qoder.payment.facade.trade.TradeFacade tradeFacade;

    private void mirror(String table, Object pk) {
        ShardRowMirror mirror = shardRowMirror.getIfAvailable();
        if (mirror != null) {
            mirror.mirror(table, pk);
        }
    }

    @Override
    public void onConfirmed(String xid) {
        long now = System.currentTimeMillis();
        long nextRetryAt = mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L;
        OutboxPublisher publisher = mqProps.isEnabled() ? publisherProvider.getIfAvailable() : null;
        List<Runnable> publishes = new ArrayList<>();

        PaymentDO payment = paymentMapper.selectByXid(xid);
        if (payment != null && "PROCESSING".equals(payment.getStatus())) {
            paymentMapper.finish(payment.getId(), "SUCCESS", null);
            mirror("payment", payment.getId());
            if (isComboMode(payment.getPayMode())) {
                legMapper.finishByPaymentId(payment.getId(), "SUCCESS");
            }

            SettlePayRequest settle = new SettlePayRequest();
            settle.setPaymentId(payment.getId());
            settle.setOrderId(payment.getOrderId());
            settle.setMerchantId(payment.getMerchantId());
            settle.setAmount(payment.getAmount());
            settle.setFee(payment.getFee());
            settle.setMethod(payment.getMethod());
            String settlePayload = TccContextUtil.toJson(settle);
            outboxMapper.insertRouted("SETTLE_PAY", payment.getId(), settlePayload, nextRetryAt, now, topicRouter.route("SETTLE_PAY"));
            capture(publishes, publisher, "SETTLE_PAY", payment.getId(), settlePayload);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", payment.getId());
            payload.put("orderId", payment.getOrderId());
            payload.put("amount", payment.getAmount());
            payload.put("status", "SUCCESS");
            String notifyPayload = TccContextUtil.toJson(notify("PAY_SUCCESS", payment.getId(), payload, payment.getCallbackUrl()));
            outboxMapper.insertRouted("NOTIFY_PAY", payment.getId(), notifyPayload, nextRetryAt, now, topicRouter.route("NOTIFY_PAY"));
            capture(publishes, publisher, "NOTIFY_PAY", payment.getId(), notifyPayload);
            log.info("支付单提交成功 paymentId={} xid={}", payment.getId(), xid);
            MetricsSupport.paySuccess(payment.getMethod(), payment.getBizType());
        }
        RefundDO refund = refundMapper.selectByXid(xid);
        if (refund != null && "PROCESSING".equals(refund.getStatus())) {
            refundMapper.finish(refund.getId(), "SUCCESS");
            mirror("refund_record", refund.getId());
            PaymentDO pay = paymentMapper.selectById(refund.getPaymentId());
            paymentMapper.updateRefunded(pay.getId(),
                    pay.getRefundedAmount() + refund.getAmount(),
                    pay.getRefundedFee() + refund.getFeeRefund());
            mirror("payment", pay.getId());
            // P-2 组合退款：按受理时落库的腿分摊快照累加腿已退（与主单 refunded_amount 同一事务）
            // 口径须与受理侧一致用 isComboMode：MERGE_COMBO（合并+组合叠加）同样按腿分摊，
            // 硬编码 "COMBO" 会漏掉叠加单，导致主单已退而腿级恒 0（Σ腿已退不守恒）。
            if (isComboMode(pay.getPayMode()) && refund.getLegAllocJson() != null && !refund.getLegAllocJson().isBlank()) {
                LegAllocDO[] allocs = TccContextUtil.fromJson(refund.getLegAllocJson(), LegAllocDO[].class);
                if (allocs != null) {
                    for (LegAllocDO alloc : allocs) {
                        legMapper.addRefunded(alloc.getLegId(),
                                alloc.getAmount() == null ? 0L : alloc.getAmount(),
                                alloc.getFee() == null ? 0L : alloc.getFee());
                    }
                }
            }

            SettleRefundRequest settle = new SettleRefundRequest();
            settle.setBizId(refund.getId());
            settle.setPaymentId(refund.getPaymentId());
            settle.setAmount(refund.getAmount());
            settle.setFeeRefund(refund.getFeeRefund());
            String settlePayload = TccContextUtil.toJson(settle);
            outboxMapper.insertRouted("SETTLE_REFUND", refund.getId(), settlePayload, nextRetryAt, now, topicRouter.route("SETTLE_REFUND"));
            capture(publishes, publisher, "SETTLE_REFUND", refund.getId(), settlePayload);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", refund.getPaymentId());
            payload.put("refundId", refund.getId());
            payload.put("amount", refund.getAmount());
            String notifyPayload = TccContextUtil.toJson(notify("REFUND_SUCCESS", refund.getId(), payload, pay.getCallbackUrl()));
            outboxMapper.insertRouted("NOTIFY_REFUND", refund.getId(), notifyPayload, nextRetryAt, now, topicRouter.route("NOTIFY_REFUND"));
            capture(publishes, publisher, "NOTIFY_REFUND", refund.getId(), notifyPayload);
            log.info("退款单提交成功 refundId={} xid={}", refund.getId(), xid);
            MetricsSupport.refund("success");
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
            mirror("payment", payment.getId());
            if (isComboMode(payment.getPayMode())) {
                legMapper.finishByPaymentId(payment.getId(), "FAILED");
            }
            log.info("支付单回滚失败 paymentId={} xid={} reason={}", payment.getId(), xid, reason);
            MetricsSupport.payFail(payment.getMethod(), payment.getBizType());
            // TCC 回滚必须重开合并单：原先只有受理前失败(xid==null)与幂等让位路径调 reopenMerge，
            // 子订单 Try 失败走 TCC 回滚时合并单永久停在 PAYING（钱分文未动却悬挂），违反零悬挂。
            if (payment.getMergeId() != null && !payment.getMergeId().isBlank()) {
                String mergeId = payment.getMergeId();
                Runnable reopen = () -> {
                    try {
                        tradeFacade.reopenMerge(mergeId);
                        log.info("TCC 回滚重开合并单 mergeId={} paymentId={}", mergeId, payment.getId());
                    } catch (RuntimeException ex) {
                        log.error("TCC 回滚重开合并单失败（下次 payMerge 的 PAYING 续驱兜底）mergeId={}: {}",
                                mergeId, ex.getMessage());
                    }
                };
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            reopen.run();
                        }
                    });
                } else {
                    reopen.run();
                }
            }
            // P2-E P-7：营销抵扣随全局事务回滚而退回（券/红包/积分/活动预算 + 资金反向 + 目标维度重分摊）。
            // 本地事务内只置 ROLLBACK_PENDING（零 RPC），afterCommit 再执行退回；崩溃窗口由 marketingRollback 作业兜底。
            if (payment.getMarketingAmount() != null && payment.getMarketingAmount() > 0) {
                paymentMapper.updateMarketingStatus(payment.getId(), MarketingPayService.STATUS_ROLLBACK_PENDING);
                String paymentId = payment.getId();
                String cause = "TCC 回滚: " + reason;
                Runnable rollback = () -> marketingPayService.rollbackQuietly(paymentId, cause);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            rollback.run();
                        }
                    });
                } else {
                    rollback.run();
                }
            }
        }
        RefundDO refund = refundMapper.selectByXid(xid);
        if (refund != null && "PROCESSING".equals(refund.getStatus())) {
            refundMapper.finish(refund.getId(), "FAILED");
            mirror("refund_record", refund.getId());
            log.info("退款单回滚失败 refundId={} xid={} reason={}", refund.getId(), xid, reason);
            MetricsSupport.refund("fail");
        }
    }

    private static void capture(List<Runnable> publishes, OutboxPublisher publisher,
                                String event, String bizId, String payload) {
        if (publisher != null) {
            publishes.add(() -> publisher.publish(event, bizId, payload));
        }
    }

    /** P2-H additive：MERGE_COMBO（合并+组合叠加）与 COMBO 同样按腿落终态；SINGLE/MERGE/STAGE 不变。 */
    static boolean isComboMode(String payMode) {
        return "COMBO".equals(payMode) || "MERGE_COMBO".equals(payMode);
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