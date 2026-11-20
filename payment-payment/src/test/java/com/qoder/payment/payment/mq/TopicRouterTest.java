package com.qoder.payment.payment.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-4 topic 路由单测（§5.2「多 topic 分流漏投」护航）：
 * 路由表逐事件断言 + 回滚单 topic + 未知事件保守兜底 + 消费订阅并集。
 */
class TopicRouterTest {

    private static MqProperties props(boolean multiTopic) {
        MqProperties p = new MqProperties();
        p.setMultiTopic(multiTopic);
        return p;
    }

    @Test
    void payEventsRouteToPpPay() {
        TopicRouter router = new TopicRouter(props(true));
        assertEquals("PP_PAY", router.route("SETTLE_PAY"));
        assertEquals("PP_PAY", router.route("NOTIFY_PAY"));
    }

    @Test
    void refundEventsRouteToPpRefund() {
        TopicRouter router = new TopicRouter(props(true));
        assertEquals("PP_REFUND", router.route("SETTLE_REFUND"));
        assertEquals("PP_REFUND", router.route("NOTIFY_REFUND"));
    }

    @Test
    void futureEventsRouteToReservedTopics() {
        TopicRouter router = new TopicRouter(props(true));
        assertEquals("PP_SHARE", router.route("SHARE"));
        assertEquals("PP_SHARE", router.route("SHARE_BACK"));
        assertEquals("PP_SETTLE", router.route("SETTLE_BATCH"));
        assertEquals("PP_SIGN", router.route("SIGN"));
    }

    @Test
    void unknownEventFallsBackToLegacyNeverDropped() {
        TopicRouter router = new TopicRouter(props(true));
        assertEquals("PP_OUTBOX", router.route("SOME_FUTURE_EVENT"));
        assertEquals("PP_OUTBOX", router.route(null));
    }

    @Test
    void rollbackSwitchRoutesEverythingToLegacySingleTopic() {
        TopicRouter router = new TopicRouter(props(false));
        for (String event : new String[]{"SETTLE_PAY", "NOTIFY_PAY", "SETTLE_REFUND", "NOTIFY_REFUND",
                "SHARE", "SETTLE_BATCH", "SIGN", "WHATEVER"}) {
            assertEquals("PP_OUTBOX", router.route(event));
        }
    }

    @Test
    void consumerSubscribesUnionRegardlessOfSwitch() {
        for (boolean multi : new boolean[]{true, false}) {
            var topics = new TopicRouter(props(multi)).consumerTopics();
            assertTrue(topics.containsAll(java.util.List.of(
                    "PP_OUTBOX", "PP_PAY", "PP_REFUND", "PP_SHARE", "PP_SETTLE", "PP_SIGN")));
            assertEquals(6, topics.size());
        }
    }

    @Test
    void topicNamesAreConfigurable() {
        MqProperties p = props(true);
        p.setPayTopic("CUSTOM_PAY");
        p.setTopic("CUSTOM_LEGACY");
        TopicRouter router = new TopicRouter(p);
        assertEquals("CUSTOM_PAY", router.route("SETTLE_PAY"));
        assertEquals("CUSTOM_LEGACY", router.route("UNKNOWN_EVENT"));
        assertTrue(router.consumerTopics().contains("CUSTOM_LEGACY"));
    }
}