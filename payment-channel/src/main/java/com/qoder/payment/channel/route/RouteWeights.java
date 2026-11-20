package com.qoder.payment.channel.route;

/**
 * CH-3 打分权重快照（I-2 动态下发，随决策结果透出以便观测/复现）。
 *
 * @param success 成功率权重（成功率×100 计入）
 * @param cost    成本权重（渠道 feeBps/100 扣减；仅路由成本，不影响支付手续费口径）
 * @param latency 延迟权重（平均耗时秒数扣减）
 * @param config  配置权重（channel_config.weight/100 加成，人工偏好/灰度）
 */
public record RouteWeights(double success, double cost, double latency, double config) {

    @Override
    public String toString() {
        return "success=" + success + ",cost=" + cost + ",latency=" + latency + ",config=" + config;
    }
}