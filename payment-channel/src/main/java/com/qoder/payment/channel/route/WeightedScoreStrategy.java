package com.qoder.payment.channel.route;

import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.facade.channel.ChannelMetricsDTO;
import org.springframework.stereotype.Component;

/**
 * CH-3 默认策略：加权评分 = 成功率×100×wSuccess − 费率(bp/100)×wCost − 平均耗时(s)×wLatency
 * + 配置权重(/100)×wConfig。限额/维护/熔断为硬过滤（Router 前置），不参与打分。
 *
 * <p>出厂默认下 MOCK_BANK（weight=100,feeBps=60）得分高于 MOCK_BANK_B（weight=10,feeBps=30），
 * 保证默认 stack 路由结果与重构前「直连模拟银行」一致（§0.7 同步语义红线）。
 */
@Component
public class WeightedScoreStrategy implements RouteStrategy {

    public static final String NAME = "weighted-score";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public double score(ChannelConfigDO config, ChannelMetricsDTO metrics, RouteWeights w) {
        double successRate = metrics == null || metrics.getSuccessRate() == null ? 1.0 : metrics.getSuccessRate();
        long avgCostMs = metrics == null || metrics.getAvgCostMs() == null ? 0L : metrics.getAvgCostMs();
        int feeBps = config.getFeeBps() == null ? 0 : config.getFeeBps();
        int weight = config.getWeight() == null ? 0 : config.getWeight();
        return w.success() * successRate * 100.0
                - w.cost() * (feeBps / 100.0)
                - w.latency() * (avgCostMs / 1000.0)
                + w.config() * (weight / 100.0);
    }
}