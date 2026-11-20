package com.qoder.payment.payment.mq;

/**
 * 半消息本地事务判定上下文（sendMessageInTransaction 的 arg）。
 * executeLocalTransaction 回填判定结果与 outbox 行 id，供发送方记录半消息回执（additive 元数据）。
 * 跨线程可见性：volatile（判定线程与发送线程可能不同）。
 */
class MqTxCtx {

    final String event;
    final String bizId;
    volatile String decision = "UNKNOWN";
    volatile Long outboxId;

    MqTxCtx(String event, String bizId) {
        this.event = event;
        this.bizId = bizId;
    }
}