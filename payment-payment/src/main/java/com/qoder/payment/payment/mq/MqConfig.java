package com.qoder.payment.payment.mq;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 主投递装配（仅 payment.mq.enabled=true 时生效，docker profile）。
 * 本地 H2 模式不加载任何 MQ bean，保持零外部依赖。
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    public DefaultMQProducer outboxProducer(MqProperties props) {
        DefaultMQProducer p = new DefaultMQProducer(props.getProducerGroup());
        p.setNamesrvAddr(props.getNamesrv());
        p.setSendMsgTimeout(3000);
        p.setRetryTimesWhenSendFailed(1);
        return p;
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    public OutboxPublisher outboxPublisher(DefaultMQProducer outboxProducer, MqProperties props) {
        return new RocketMqOutboxPublisher(outboxProducer, props);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    public DefaultMQPushConsumer outboxConsumer(MqProperties props, OutboxMessageListener listener) {
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getConsumerGroup());
        c.setNamesrvAddr(props.getNamesrv());
        try {
            c.subscribe(props.getTopic(), "*");
        } catch (Exception e) {
            throw new IllegalStateException("订阅 outbox topic 失败: " + props.getTopic(), e);
        }
        c.registerMessageListener(listener);
        return c;
    }
}