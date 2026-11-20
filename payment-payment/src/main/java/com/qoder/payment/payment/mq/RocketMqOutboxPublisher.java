package com.qoder.payment.payment.mq;

import com.qoder.payment.payment.mapper.OutboxMapper;
import com.qoder.tcc.TccContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocketMQ 主投递（I-4）：事务提交后把 outbox 事件推送到 broker，消费端毫秒级触发投递。
 *
 * <p>两种模式（互斥装配，见 MqConfig）：
 * <ul>
 *   <li>事务消息（transactional=true）：sendMessageInTransaction 先发半消息 →
 *       OutboxTransactionListener 按 outbox 行判定（本地事务已在 TCC 终态同事务提交）→
 *       broker commit/rollback；崩溃窗口由回查兜底。发送后记录 msg_id/half_state（additive 留痕）。</li>
 *   <li>普通消息（transactional=false，回滚路径）：afterCommit 直接 send，与升级前逐字节一致。</li>
 * </ul>
 *
 * <p>红线：发送失败绝不抛出（本地事务已提交），事件留 PENDING 由 3s 轮询兜底，保证不丢；
 * outbox 始终是唯一事实来源。消息体 {event,bizId,payload}、tags=event、keys=bizId 保持不变，
 * 消费端 deliverByEventBizId 同一幂等路径。
 */
@Slf4j
public class RocketMqOutboxPublisher implements OutboxPublisher {

    private final DefaultMQProducer producer;
    private final TransactionMQProducer txProducer;
    private final TopicRouter router;
    private final OutboxMapper outboxMapper;

    public RocketMqOutboxPublisher(DefaultMQProducer producer, TransactionMQProducer txProducer,
                                   TopicRouter router, OutboxMapper outboxMapper) {
        this.producer = producer;
        this.txProducer = txProducer;
        this.router = router;
        this.outboxMapper = outboxMapper;
    }

    @Override
    public void publish(String event, String bizId, String payload) {
        MqTxCtx ctx = new MqTxCtx(event, bizId);
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("event", event);
            body.put("bizId", bizId);
            body.put("payload", payload);
            Message msg = new Message(router.route(event), event, bizId,
                    TccContextUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
            if (txProducer != null) {
                // 半消息 → 本地事务判定（outbox 行已随 TCC 终态提交）→ commit/rollback
                SendResult sr = txProducer.sendMessageInTransaction(msg, ctx);
                log.debug("outbox 半消息已发 event={} bizId={} msgId={} decision={}",
                        event, bizId, sr.getMsgId(), ctx.decision);
                recordMeta(ctx.outboxId, sr.getMsgId(), ctx.decision);
            } else {
                producer.send(msg);
                log.debug("outbox MQ 主投递 event={} bizId={}", event, bizId);
            }
        } catch (Exception e) {
            // 事务已提交不能抛：outbox 留 PENDING，轮询兜底（与升级前一致）
            log.warn("outbox MQ 主投递失败，留待轮询兜底 event={} bizId={}: {}", event, bizId, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 半消息回执留痕（additive：只写 msg_id/half_state，绝不动 status/attempts/next_retry_at）。 */
    private void recordMeta(Long outboxId, String msgId, String halfState) {
        if (outboxId == null) {
            return;
        }
        try {
            outboxMapper.updateMqMeta(outboxId, msgId, halfState);
        } catch (Exception e) {
            log.warn("outbox 半消息回执留痕失败 id={}: {}", outboxId, e.getMessage());
        }
    }
}