package com.qoder.payment.channel.route;

import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.facade.channel.ChannelMetricsDTO;

/**
 * CH-3 路由打分策略 SPI：可插拔（成功率/成本/延迟/权重加权评分为默认实现）。
 * 新增策略只需实现本接口并注册 Bean，{@link ChannelRouter} 按 {@code channel.route.strategy} 选择。
 */
public interface RouteStrategy {

    /** 策略标识（weighted-score / cost-first / success-first ...）。 */
    String name();

    /** 分值越大越优先；硬过滤（限额/维护/熔断）不在打分内，由 Router 前置排除。 */
    double score(ChannelConfigDO config, ChannelMetricsDTO metrics, RouteWeights weights);
}