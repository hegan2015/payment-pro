package com.qoder.payment.payment.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** RocketMQ 主投递配置；enabled=false 时退回 outbox 轮询（本地零依赖模式）。 */
@Data
@ConfigurationProperties(prefix = "payment.mq")
public class MqProperties {

    private boolean enabled = false;
    private String namesrv = "127.0.0.1:9876";
    private String topic = "PP_OUTBOX";
    private String producerGroup = "payment-outbox-producer";
    private String consumerGroup = "payment-outbox-consumer";
    /** MQ 主投递宽限期：outbox 行在此窗口内优先由 MQ 消费投递，超时才由轮询兜底。 */
    private long fallbackGraceMs = 4000;
}