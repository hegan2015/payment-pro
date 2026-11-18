package com.qoder.payment.payment.mq;

import com.qoder.payment.payment.service.OutboxDispatcher;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * outbox 事件 MQ 消费：调用与轮询兜底相同的幂等投递逻辑（Dubbo→清结算/通知），
 * 成功即 markDelivered；失败 RECONSUME_LATER 由 broker 重投，轮询兜底同时兜底。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OutboxMessageListener implements MessageListenerConcurrently {

    private final OutboxDispatcher dispatcher;

    @Override
    @SuppressWarnings("unchecked")
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        for (MessageExt m : msgs) {
            try {
                Map<String, String> body = TccContextUtil.fromJson(
                        new String(m.getBody(), StandardCharsets.UTF_8), Map.class);
                dispatcher.deliverByEventBizId(body.get("event"), body.get("bizId"));
            } catch (Exception e) {
                log.warn("outbox MQ 消费失败 msgId={}，重投: {}", m.getMsgId(), e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}