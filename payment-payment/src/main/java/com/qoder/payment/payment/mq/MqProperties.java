package com.qoder.payment.payment.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 主投递配置；enabled=false 时退回 outbox 轮询（本地零依赖模式）。
 *
 * <p>I-4 增量（全部 additive，回滚开关见 §5.2）：
 * <ul>
 *   <li>{@code transactional=false}（默认）→ afterCommit 普通消息（升级前路径，一键回滚）；
 *       {@code true} → TransactionMQProducer 半消息 + 本地事务判定 + 回查。</li>
 *   <li>{@code multiTopic=false}（默认）→ 全部事件走 legacy 单 topic {@code PP_OUTBOX}；
 *       {@code true} → 按 {@link TopicRouter} 分流 PP_PAY/PP_REFUND/PP_SHARE/PP_SETTLE/PP_SIGN。</li>
 *   <li>{@code enabled=false} → 不加载任何 MQ bean，纯 outbox 轮询（local 现状，可靠性语义不变）。</li>
 * </ul>
 * outbox 始终是唯一事实来源，3s 轮询兜底永不删除；MQ 只是主路径。
 */
@Data
@ConfigurationProperties(prefix = "payment.mq")
public class MqProperties {

    private boolean enabled = false;
    private String namesrv = "127.0.0.1:9876";
    /** legacy 单 topic（兼容/回滚保留）；multiTopic=true 时亦作为未知事件的保守兜底 topic。 */
    private String topic = "PP_OUTBOX";
    private String producerGroup = "payment-outbox-producer";
    private String consumerGroup = "payment-outbox-consumer";
    /** MQ 主投递宽限期：outbox 行在此窗口内优先由 MQ 消费投递，超时才由轮询兜底。 */
    private long fallbackGraceMs = 4000;

    /** I-4 事务消息开关：true=半消息+回查主路径；false=afterCommit 普通消息（回滚等价升级前）。 */
    private boolean transactional = false;

    /** I-4 多 topic 分流开关：true=按事件路由；false=全部 PP_OUTBOX（回滚单 topic）。 */
    private boolean multiTopic = false;

    /** 支付事件 topic（NOTIFY_PAY/SETTLE_PAY）。 */
    private String payTopic = "PP_PAY";
    /** 退款事件 topic（NOTIFY_REFUND/SETTLE_REFUND）。 */
    private String refundTopic = "PP_REFUND";
    /** 分账事件 topic（P-3/ST-3 SHARE* 预留）。 */
    private String shareTopic = "PP_SHARE";
    /** 结算批次事件 topic（ST-1 SETTLE_BATCH 预留）。 */
    private String settleTopic = "PP_SETTLE";
    /** 签约事件 topic（X-4 SIGN 预留）。 */
    private String signTopic = "PP_SIGN";

    /**
     * 混沌注入（仅 verify-mq.py 使用，默认 0 完全不生效）：
     * executeLocalTransaction 判定前延迟毫秒数，制造「半消息已发、commit 未达」的崩溃窗口。
     * 生产路径恒为 0，不改变任何时序。
     */
    private long txChaosDelayMs = 0;
}