package com.qoder.payment.payment.mq;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * I-4 事件→topic 路由（多 topic 分流）。
 *
 * <p>路由表：SETTLE_PAY/NOTIFY_PAY→PP_PAY；SETTLE_REFUND/NOTIFY_REFUND→PP_REFUND；
 * SHARE*（P-3/ST-3 预留）→PP_SHARE；SETTLE_BATCH（ST-1 预留）→PP_SETTLE；SIGN（X-4 预留）→PP_SIGN。
 * 未知事件保守回 legacy PP_OUTBOX，绝不漏投。
 *
 * <p>{@code multiTopic=false}（回滚开关）时所有事件走 legacy 单 topic，与升级前一致。
 * 消费端订阅恒为「legacy + 全部路由 topic」并集：滚动切换/回滚期间在途消息两边都吸收，
 * 投递仍走 {@code deliverByEventBizId} 同一幂等路径，幂等键不变。
 */
public class TopicRouter {

    private final MqProperties props;

    public TopicRouter(MqProperties props) {
        this.props = props;
    }

    /** 生产路由：事件应投递到的 topic。 */
    public String route(String event) {
        if (!props.isMultiTopic() || event == null) {
            return props.getTopic();
        }
        return switch (event) {
            case "SETTLE_PAY", "NOTIFY_PAY" -> props.getPayTopic();
            case "SETTLE_REFUND", "NOTIFY_REFUND" -> props.getRefundTopic();
            case "SETTLE_BATCH" -> props.getSettleTopic();
            case "SIGN" -> props.getSignTopic();
            default -> event.startsWith("SHARE") ? props.getShareTopic() : props.getTopic();
        };
    }

    /** 消费订阅并集（与 multiTopic 开关无关，保证切换期订阅一致、在途消息不丢）。 */
    public List<String> consumerTopics() {
        Set<String> topics = new LinkedHashSet<>();
        topics.add(props.getTopic());
        topics.add(props.getPayTopic());
        topics.add(props.getRefundTopic());
        topics.add(props.getShareTopic());
        topics.add(props.getSettleTopic());
        topics.add(props.getSignTopic());
        return List.copyOf(topics);
    }
}