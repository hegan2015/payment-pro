package com.qoder.payment.payment.mq;

import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocketMQ 主投递：事务提交后把 outbox 事件推送到 broker，消费端毫秒级触发投递。
 * 发送失败不抛出（事务已提交），事件留 PENDING 由 outbox 轮询兜底，保证不丢。
 */
@Slf4j
@RequiredArgsConstructor
public class RocketMqOutboxPublisher implements OutboxPublisher {

    private final DefaultMQProducer producer;
    private final MqProperties props;

    @Override
    public void publish(String event, String bizId, String payload) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("event", event);
            body.put("bizId", bizId);
            body.put("payload", payload);
            Message msg = new Message(props.getTopic(), event, bizId,
                    TccContextUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
            producer.send(msg);
            log.debug("outbox MQ 主投递 event={} bizId={}", event, bizId);
        } catch (Exception e) {
            log.warn("outbox MQ 主投递失败，留待轮询兜底 event={} bizId={}: {}", event, bizId, e.getMessage());
        }
    }
}