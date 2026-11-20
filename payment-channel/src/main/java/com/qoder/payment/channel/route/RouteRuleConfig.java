package com.qoder.payment.channel.route;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelRouteRuleDO;
import com.qoder.payment.channel.mapper.ChannelRouteRuleMapper;
import com.qoder.payment.common.config.DynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CH-3 路由规则与动态参数（I-2）：权重/窗口/熔断/切换开关全部经 DynamicConfig 下发，
 * 结构化规则（金额区间 + 卡 BIN → 渠道权重覆盖）落 channel_route_rule，版本化可回滚。
 *
 * <p>规则语义：priority 越小越优先，命中即用该规则的 weight 覆盖渠道配置权重（灰度/偏好），
 * 不改变硬过滤（限额/维护/熔断仍前置）。规则为空 → 纯 channel_config 权重打分（默认行为）。
 * 配置中心不可达 → 代码默认值兜底，绝不阻断支付。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteRuleConfig {

    private final DynamicConfig config;
    private final ChannelRouteRuleMapper ruleMapper;

    public boolean routeEnabled() {
        return config.getBool(ChannelConfigKeys.ROUTE_ENABLED, ChannelConfigKeys.DEFAULT_ROUTE_ENABLED);
    }

    public boolean failoverEnabled() {
        return config.getBool(ChannelConfigKeys.ROUTE_FAILOVER_ENABLED, ChannelConfigKeys.DEFAULT_FAILOVER_ENABLED);
    }

    public int maxAttempts() {
        int v = config.getInt(ChannelConfigKeys.ROUTE_MAX_ATTEMPTS, ChannelConfigKeys.DEFAULT_MAX_ATTEMPTS);
        return Math.max(1, Math.min(v, 8));
    }

    public String defaultChannel() {
        return config.get(ChannelConfigKeys.ROUTE_DEFAULT_CHANNEL, ChannelConfigKeys.DEFAULT_ROUTE_CHANNEL);
    }

    /** 主渠道偏好加成（默认 30 分，设 0 = 纯评分择优）。 */
    public double primaryBonus() {
        return doubleOf(ChannelConfigKeys.ROUTE_PRIMARY_BONUS, ChannelConfigKeys.DEFAULT_PRIMARY_BONUS);
    }

    public String strategyName() {
        return config.get("channel.route.strategy", WeightedScoreStrategy.NAME);
    }

    public RouteWeights weights() {
        return new RouteWeights(
                doubleOf(ChannelConfigKeys.ROUTE_WEIGHT_SUCCESS, ChannelConfigKeys.DEFAULT_WEIGHT_SUCCESS),
                doubleOf(ChannelConfigKeys.ROUTE_WEIGHT_COST, ChannelConfigKeys.DEFAULT_WEIGHT_COST),
                doubleOf(ChannelConfigKeys.ROUTE_WEIGHT_LATENCY, ChannelConfigKeys.DEFAULT_WEIGHT_LATENCY),
                doubleOf(ChannelConfigKeys.ROUTE_WEIGHT_CONFIG, ChannelConfigKeys.DEFAULT_WEIGHT_CONFIG));
    }

    private double doubleOf(String key, double def) {
        String raw = config.get(key, null);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("路由权重配置非法（用默认值）key={} value={}", key, raw);
            return def;
        }
    }

    public List<ChannelRouteRuleDO> enabledRules() {
        try {
            return ruleMapper.selectEnabled();
        } catch (Exception e) {
            log.warn("路由规则读取失败（降级为无规则）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 命中规则的权重覆盖：channelCode → weight（priority 小者优先，先命中先赢）。 */
    public java.util.Map<String, Integer> weightOverrides(Long amount, String cardBin) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (ChannelRouteRuleDO rule : enabledRules()) {
            if (!matches(rule, amount, cardBin)) {
                continue;
            }
            out.putIfAbsent(rule.getChannelCode(), rule.getWeight() == null ? 0 : rule.getWeight());
        }
        return out;
    }

    private static boolean matches(ChannelRouteRuleDO rule, Long amount, String cardBin) {
        long amt = amount == null ? 0L : amount;
        if (rule.getMinAmount() != null && rule.getMinAmount() > 0 && amt < rule.getMinAmount()) {
            return false;
        }
        if (rule.getMaxAmount() != null && rule.getMaxAmount() > 0 && amt > rule.getMaxAmount()) {
            return false;
        }
        if (rule.getCardBin() != null && !rule.getCardBin().isBlank()) {
            if (cardBin == null || !cardBin.startsWith(rule.getCardBin().trim())) {
                return false;
            }
        }
        return true;
    }
}