package com.qoder.payment.channel.route;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.service.ChannelConfigService;
import com.qoder.payment.channel.service.ChannelLimitService;
import com.qoder.payment.channel.service.MaintenanceWindowService;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.sentinel.SentinelGuard;
import com.qoder.payment.facade.channel.ChannelMetricsDTO;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import com.qoder.payment.facade.channel.RouteResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CH-3 渠道路由：硬过滤（限额/维护窗口/熔断/异步 opt-in/停用）+ 加权评分择优 + 故障切换候选序列。
 *
 * <p><b>不产生悬挂单的三条护栏</b>：
 * <ol>
 *   <li>全部硬过滤发生在<b>受理前</b>（channel_trade 尚未落行），无可用渠道 → 抛 BIZ_ERROR，
 *       TCC Try 失败 → Cancel 空回滚 → 支付 FAILED、订单 CREATED、无账务单据；</li>
 *   <li>故障切换只沿「eligible 候选序列」向后取，且只在适配器明确报告<b>确未受理</b>
 *       （{@code ChannelResult.failoverAllowed=true}）时切换；已受理后状态未知一律 PROCESSING，
 *       交查询补单收敛，绝不二次受理（channel_trade.biz_no PK 结构性保证一单一渠道一行）；</li>
 *   <li>指定渠道不可用 → 直接拒绝，不静默改道（商户指定语义优先且可观测）。</li>
 * </ol>
 *
 * <p>退款沿用原扣款渠道，不受维护窗口/限额/熔断阻断（资金必须可退）。
 */
@Slf4j
@Service
public class ChannelRouter {

    private final ChannelConfigService configService;
    private final ChannelAdapterRegistry registry;
    private final ChannelLimitService limitService;
    private final MaintenanceWindowService maintenanceService;
    private final ChannelMetricsCollector metrics;
    private final RouteRuleConfig ruleConfig;
    private final DynamicConfig config;
    private final Map<String, RouteStrategy> strategies = new java.util.LinkedHashMap<>();

    @Value("${payment.channel.async:false}")
    private boolean async;

    public ChannelRouter(ChannelConfigService configService, ChannelAdapterRegistry registry,
                         ChannelLimitService limitService, MaintenanceWindowService maintenanceService,
                         ChannelMetricsCollector metrics, RouteRuleConfig ruleConfig,
                         DynamicConfig config, List<RouteStrategy> strategyBeans) {
        this.configService = configService;
        this.registry = registry;
        this.limitService = limitService;
        this.maintenanceService = maintenanceService;
        this.metrics = metrics;
        this.ruleConfig = ruleConfig;
        this.config = config;
        for (RouteStrategy s : strategyBeans) {
            strategies.put(s.name(), s);
        }
    }

    /** 决策预览（不抛异常）：无可用渠道时 channelCode=null + 原因，供控制台/验收观测。 */
    public RouteResultDTO route(ChannelRouteRequest request) {
        ChannelRouteRequest req = request == null ? new ChannelRouteRequest() : request;
        long amount = req.getAmount() == null ? 0L : req.getAmount();
        String bin = cardBin(req);
        boolean refund = "REFUND".equalsIgnoreCase(req.getType());
        String specified = blankToNull(req.getChannelCode());
        RouteWeights weights = ruleConfig.weights();
        RouteStrategy strategy = strategies.getOrDefault(ruleConfig.strategyName(),
                strategies.get(WeightedScoreStrategy.NAME));
        Map<String, Integer> overrides = ruleConfig.weightOverrides(amount, bin);

        RouteResultDTO out = new RouteResultDTO();
        out.setDecidedAt(System.currentTimeMillis());
        out.setSpecified(specified != null);
        out.setStrategy(strategy == null ? WeightedScoreStrategy.NAME : strategy.name());
        out.setWeights(weights.toString());

        List<RouteResultDTO.Candidate> candidates = new ArrayList<>();
        for (ChannelConfigDO c : configService.list()) {
            if (specified != null && !specified.equals(c.getChannelCode())) {
                continue;
            }
            candidates.add(evaluate(c, amount, refund, overrides, weights, strategy));
        }
        if (specified != null && candidates.isEmpty()) {
            RouteResultDTO.Candidate missing = new RouteResultDTO.Candidate();
            missing.setChannelCode(specified);
            missing.setEligible(false);
            missing.setReason("渠道未配置/未注册");
            candidates.add(missing);
        }
        Comparator<RouteResultDTO.Candidate> byEligible =
                Comparator.comparing(RouteResultDTO.Candidate::isEligible, Comparator.reverseOrder());
        Comparator<RouteResultDTO.Candidate> byScore = Comparator.comparingDouble(
                (RouteResultDTO.Candidate x) -> x.getScore() == null ? Double.NEGATIVE_INFINITY : x.getScore()).reversed();
        Comparator<RouteResultDTO.Candidate> byWeight = Comparator.comparingInt(
                (RouteResultDTO.Candidate x) -> x.getWeight() == null ? 0 : x.getWeight()).reversed();
        Comparator<RouteResultDTO.Candidate> byCode = Comparator.comparing(
                (RouteResultDTO.Candidate x) -> x.getChannelCode() == null ? "" : x.getChannelCode());
        candidates.sort(byEligible.thenComparing(byScore).thenComparing(byWeight).thenComparing(byCode));
        out.setCandidates(candidates);

        RouteResultDTO.Candidate winner = candidates.stream().filter(RouteResultDTO.Candidate::isEligible)
                .findFirst().orElse(null);
        if (winner != null) {
            out.setChannelCode(winner.getChannelCode());
            out.setScore(winner.getScore());
            out.setReason((specified != null ? "指定渠道可用: " : "择优命中: ") + winner.getReason());
        } else {
            out.setChannelCode(null);
            out.setReason("无可用渠道: " + candidates.stream()
                    .map(x -> x.getChannelCode() + "(" + x.getReason() + ")")
                    .reduce((a, b) -> a + "; " + b).orElse("无任何渠道配置"));
        }
        MetricsSupport.count("channel_route", "channel", out.getChannelCode() == null ? "NONE" : out.getChannelCode(),
                "type", refund ? "REFUND" : "PAY");
        log.info("CH-3 路由决策 amount={} bin={} specified={} selected={} reason={}",
                amount, bin, specified, out.getChannelCode(), out.getReason());
        return out;
    }

    /** 受理前路由（无可用渠道/指定渠道不可用 → 拒绝受理，不留悬挂单）。 */
    public RouteResultDTO routeForAccept(ChannelRouteRequest request) {
        RouteResultDTO result = route(request);
        if (result.getChannelCode() == null) {
            MetricsSupport.count("channel_route_reject");
            throw new BizException(ResultCode.BIZ_ERROR, "渠道路由拒绝: " + result.getReason());
        }
        return result;
    }

    /** 故障切换候选序列（按评分降序的 eligible 渠道）。 */
    public List<String> eligibleOrder(RouteResultDTO result) {
        List<String> codes = new ArrayList<>();
        for (RouteResultDTO.Candidate c : result.getCandidates()) {
            if (c.isEligible()) {
                codes.add(c.getChannelCode());
            }
        }
        return codes;
    }

    private RouteResultDTO.Candidate evaluate(ChannelConfigDO c, long amount, boolean refund,
                                              Map<String, Integer> overrides, RouteWeights weights,
                                              RouteStrategy strategy) {
        String code = c.getChannelCode();
        RouteResultDTO.Candidate cand = new RouteResultDTO.Candidate();
        cand.setChannelCode(code);
        cand.setFeeBps(c.getFeeBps());
        cand.setWeight(overrides.getOrDefault(code, c.getWeight()));
        cand.setSingleLimit(c.getSingleLimit());
        cand.setInMaintenance(maintenanceService.inMaintenance(code));
        cand.setBreakerOpen(metrics.breakerOpen(code));
        ChannelMetricsDTO snapshot = metrics.snapshot(code);
        cand.setSuccessRate(snapshot.getSuccessRate());
        cand.setAvgCostMs(snapshot.getAvgCostMs());
        long[] limits = limitService.effectiveLimits(code);
        cand.setSingleLimit(limits[0] > 0 ? limits[0] : null);
        cand.setDailyRemaining(limits[1] > 0 ? Math.max(0L, limits[1] - limitService.usedAmount(code,
                ChannelLimitService.bizDate(System.currentTimeMillis()))) : null);

        ChannelAdapter adapter = registry.find(code);
        if (adapter == null) {
            return deny(cand, "适配器未注册");
        }
        if ("DISABLED".equalsIgnoreCase(c.getStatus())) {
            return deny(cand, "渠道停用(DISABLED)");
        }
        if (refund) {
            // 退款沿用原渠道：不受维护/限额/熔断阻断（资金可退优先）
            cand.setEligible(true);
            cand.setScore(strategy == null ? 0 : strategy.score(c, snapshot, weights));
            cand.setReason("退款沿用原渠道（不受维护/限额/熔断阻断）");
            return cand;
        }
        if (adapter.asyncCapable() && !async) {
            return deny(cand, "异步渠道需开启 PAYMENT_CHANNEL_ASYNC=true（同步 stack 排除，杜绝悬挂单）");
        }
        if ("SANDBOX".equalsIgnoreCase(adapter.adapterType())
                && !config.getBool(ChannelConfigKeys.SANDBOX_ENABLED, ChannelConfigKeys.DEFAULT_SANDBOX_ENABLED)) {
            return deny(cand, "沙箱渠道开关关闭(channel.sandbox.enabled=false)");
        }
        if (cand.isInMaintenance()) {
            return deny(cand, "维护窗口内 " + maintenanceService.describe(code));
        }
        if (limits[0] > 0 && amount > limits[0]) {
            return deny(cand, "超出渠道单笔限额 " + limits[0] + " 分");
        }
        if (limits[1] > 0 && limitService.usedAmount(code, ChannelLimitService.bizDate(System.currentTimeMillis()))
                + amount > limits[1]) {
            return deny(cand, "超出渠道日限额 " + limits[1] + " 分（当日已用 "
                    + limitService.usedAmount(code, ChannelLimitService.bizDate(System.currentTimeMillis())) + "）");
        }
        if (cand.isBreakerOpen()) {
            return deny(cand, "渠道熔断中（失败率超阈值，自动绕行）");
        }
        if (!SentinelGuard.tryPass("channel:" + code)) {
            return deny(cand, "Sentinel 熔断/限流");
        }
        ChannelConfigDO scored = c;
        if (overrides.containsKey(code)) {
            scored = new ChannelConfigDO();
            org.springframework.beans.BeanUtils.copyProperties(c, scored);
            scored.setWeight(overrides.get(code));
        }
        double score = strategy == null ? 0 : strategy.score(scored, snapshot, weights);
        boolean primary = code.equals(ruleConfig.defaultChannel());
        if (primary) {
            score += ruleConfig.primaryBonus();
        }
        cand.setEligible(true);
        cand.setScore(score);
        cand.setWeight(scored.getWeight());
        cand.setReason(String.format("score=%.3f 成功率=%.2f 成本=%dbp 耗时=%dms 权重=%d%s%s",
                score, snapshot.getSuccessRate(), scored.getFeeBps() == null ? 0 : scored.getFeeBps(),
                snapshot.getAvgCostMs(), scored.getWeight() == null ? 0 : scored.getWeight(),
                overrides.containsKey(code) ? "（规则覆盖）" : "",
                primary ? "（主渠道偏好+" + ruleConfig.primaryBonus() + "）" : ""));
        return cand;
    }

    private static RouteResultDTO.Candidate deny(RouteResultDTO.Candidate cand, String reason) {
        cand.setEligible(false);
        cand.setReason(reason);
        return cand;
    }

    private static String cardBin(ChannelRouteRequest req) {
        if (req.getCardBin() != null && !req.getCardBin().isBlank()) {
            return req.getCardBin().trim();
        }
        String cardNo = req.getCardNo();
        if (cardNo == null) {
            return null;
        }
        String digits = cardNo.replaceAll("\\D", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : (digits.isEmpty() ? null : digits);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}