package com.qoder.payment.payment.mq;

import com.qoder.payment.payment.mapper.OutboxMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 主投递装配（仅 payment.mq.enabled=true 时生效，docker profile）。
 * 本地 H2 模式不加载任何 MQ bean，保持零外部依赖。
 *
 * <p>I-4：transactional=true 装配 TransactionMQProducer（半消息+回查）；
 * false（默认/回滚）装配升级前的 DefaultMQProducer 普通消息。二者互斥，绝不共存。
 * 消费端恒订阅「legacy PP_OUTBOX + 全部路由 topic」并集（切换/回滚期在途消息都吸收），
 * 投递仍走 deliverByEventBizId 同一幂等路径。
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqConfig {

    /**
     * 生产者/消费者共享同一 MQClientInstance 的关键：显式且相同的 instanceName。
     * 默认 instanceName 会被各客户端各自改成 pid#nanoTime → 生产/消费分裂成两个工厂，
     * 纯生产者工厂在首次发送前没有 topic 路由、不发心跳 → broker 生产者组通道表为空 →
     * 重启后静默期事务回查「channel table is empty」无法路由（I-4 混沌实测根因）。
     * 共享工厂后，消费者订阅路由启动即拉取，心跳同时注册生产者组，回查随时可达。
     * pid 后缀保证同机多实例（I-9 预留）clientId 不冲突。
     */
    private static final String CLIENT_INSTANCE_NAME = "payment-payment-" + ProcessHandle.current().pid();

    @Bean
    public TopicRouter topicRouter(MqProperties props) {
        return new TopicRouter(props);
    }

    /** 回滚/兼容路径：afterCommit 普通消息（升级前装配，逐字节保留）。 */
    @Configuration
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    static class NormalProducerConfig {

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        @ConditionalOnProperty(prefix = "payment.mq", name = "transactional", havingValue = "false", matchIfMissing = true)
        public DefaultMQProducer outboxProducer(MqProperties props) {
            DefaultMQProducer p = new DefaultMQProducer(props.getProducerGroup());
            p.setNamesrvAddr(props.getNamesrv());
            p.setInstanceName(CLIENT_INSTANCE_NAME);
            p.setSendMsgTimeout(3000);
            p.setRetryTimesWhenSendFailed(1);
            return p;
        }
    }

    /** I-4 主路径：事务消息生产者 + 本地事务判定/回查监听器。 */
    @Configuration
    @ConditionalOnProperty(prefix = "payment.mq", name = {"enabled", "transactional"}, havingValue = "true")
    static class TransactionProducerConfig {

        @Bean
        public OutboxTransactionListener outboxTransactionListener(OutboxMapper outboxMapper, MqProperties props) {
            return new OutboxTransactionListener(outboxMapper, props);
        }

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        public TransactionMQProducer outboxTxProducer(MqProperties props, OutboxTransactionListener listener) {
            // producerGroup 与普通模式一致：重启后 broker 按同组回查到新实例
            TransactionMQProducer p = new TransactionMQProducer(props.getProducerGroup());
            p.setNamesrvAddr(props.getNamesrv());
            p.setInstanceName(CLIENT_INSTANCE_NAME);
            p.setSendMsgTimeout(3000);
            p.setRetryTimesWhenSendFailed(1);
            p.setTransactionListener(listener);
            return p;
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    public OutboxPublisher outboxPublisher(MqProperties props, TopicRouter router, OutboxMapper outboxMapper,
                                           ObjectProvider<DefaultMQProducer> normalProducer,
                                           ObjectProvider<TransactionMQProducer> txProducer) {
        // TransactionMQProducer 是 DefaultMQProducer 子类：显式互斥，事务模式绝不走普通 send
        TransactionMQProducer tx = txProducer.getIfAvailable();
        DefaultMQProducer normal = tx == null ? normalProducer.getIfAvailable() : null;
        return new RocketMqOutboxPublisher(normal, tx, router, outboxMapper);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true")
    public DefaultMQPushConsumer outboxConsumer(MqProperties props, TopicRouter router, OutboxMessageListener listener) {
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getConsumerGroup());
        c.setNamesrvAddr(props.getNamesrv());
        c.setInstanceName(CLIENT_INSTANCE_NAME);
        try {
            // 订阅并集：multiTopic 开/关、滚动切换期间订阅一致，在途消息不丢（幂等吸收双投）
            for (String topic : router.consumerTopics()) {
                c.subscribe(topic, "*");
            }
        } catch (Exception e) {
            throw new IllegalStateException("订阅 outbox topic 失败: " + router.consumerTopics(), e);
        }
        c.registerMessageListener(listener);
        return c;
    }
}