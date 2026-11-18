package com.qoder.payment.payment.mq;

/** outbox 事件主投递出口；MQ 关闭时无实现，轮询兜底独立工作。 */
public interface OutboxPublisher {

    void publish(String event, String bizId, String payload);
}