package com.qoder.payment.risk.aml;

import com.qoder.payment.facade.risk.AmlRuleDTO;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.risk.engine.RiskContext;
import com.qoder.payment.risk.engine.RiskRuleEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * R-6 AML 规则评估器：<b>复用既有 QLExpress 引擎</b>（{@link RiskRuleEngine}）+ 内置类型评估器。
 *
 * <p>六类可疑交易监测：LARGE_AMOUNT（大额）/ SPLIT（拆分·分散转入集中转出）/
 * STRUCTURING（结构化·贴近阈值多笔）/ FAST_IN_OUT（快进快出）/ ABNORMAL_HOUR（异常时段）/
 * CROSS_BORDER（跨境）；另有 SANCTION/PEP 名单筛查与 CUSTOM 纯表达式。
 *
 * <p><b>护栏红线（继承 P1-B §5.4）</b>：{@code conditionExpr} 非空时经既有引擎执行，
 * <b>编译失败 / 执行异常 / 超时一律视为未命中（跳过）+ degraded 告警</b>，
 * 绝不因 AML 规则错误拦截正常交易；内置评估器任何异常同样按未命中处理。
 */
@Slf4j
@Component
public class AmlRuleEngine {

    /** 聚合观测（由 AmlService 从 aml_txn 流水计算，报告 detail 落同一份口径）。 */
    public record Agg(long dayAmount, long dayCount, long maxSingle, long inAmount, long outAmount,
                      long structuringCount, long nightAmount, long nightCount,
                      long crossBorderAmount, long crossBorderCount, long fastIn, long fastOut,
                      List<Map<String, Object>> txns) {
    }

    /** 评估结果。 */
    public record Eval(boolean hit, boolean degraded, String errorKind, String error, String detail) {
        static Eval miss(String detail) {
            return new Eval(false, false, null, null, detail);
        }
    }

    private final RiskRuleEngine engine;
    private final AmlWatchlistService watchlist;

    public AmlRuleEngine(RiskRuleEngine engine, AmlWatchlistService watchlist) {
        this.engine = engine;
        this.watchlist = watchlist;
    }

    /** 编译校验（复用既有引擎缓存与告警口径）。 */
    public RiskRuleEngine.Compile compile(String expr) {
        return engine.compile(expr);
    }

    public Eval evaluate(AmlRuleDTO rule, RiskContext ctx, Agg agg) {
        String expr = rule.getConditionExpr();
        if (expr != null && !expr.isBlank()) {
            RiskRuleDTO carrier = new RiskRuleDTO();
            carrier.setId(rule.getId());
            carrier.setName(rule.getName());
            carrier.setConditionExpr(expr);
            RiskRuleEngine.Eval e = engine.evaluate(carrier, ctx);
            return new Eval(e.hit(), e.degraded(), e.errorKind(), e.error(), "QLExpress: " + expr);
        }
        try {
            return builtin(rule, ctx, agg);
        } catch (Exception e) {
            // 内置评估器异常同样按未命中 + 降级告警（绝不拦截正常交易）
            log.warn("AML 内置规则评估异常（跳过并告警）ruleId={}: {}", rule.getId(), e.getMessage());
            return new Eval(false, true, "EXEC", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "内置评估器异常");
        }
    }

    private Eval builtin(AmlRuleDTO rule, RiskContext ctx, Agg agg) {
        long threshold = rule.getThreshold() == null ? 0L : rule.getThreshold();
        int minCount = rule.getMinCount() == null ? 1 : rule.getMinCount();
        int ratio = rule.getRatio() == null ? 90 : rule.getRatio();
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().trim().toUpperCase();
        switch (type) {
            case "LARGE_AMOUNT" -> {
                boolean single = agg.maxSingle() >= threshold;
                boolean cumulative = agg.dayCount() > 1 && agg.dayAmount() >= threshold;
                return new Eval(single || cumulative, false, null, null,
                        "单笔最大 " + agg.maxSingle() + " / 日累计 " + agg.dayAmount() + "（阈值 " + threshold + "）");
            }
            case "SPLIT" -> {
                boolean hit = agg.dayCount() >= minCount && agg.dayAmount() >= threshold
                        && agg.maxSingle() < threshold;
                return new Eval(hit, false, null, null,
                        "日笔数 " + agg.dayCount() + " ≥ " + minCount + " 且日累计 " + agg.dayAmount()
                                + " ≥ " + threshold + " 且单笔均 < 阈值（拆分）");
            }
            case "STRUCTURING" -> {
                boolean hit = agg.structuringCount() >= minCount;
                return new Eval(hit, false, null, null,
                        "贴近阈值下方（≥ " + threshold + "×" + ratio + "%）笔数 " + agg.structuringCount()
                                + " ≥ " + minCount + "（结构化）");
            }
            case "FAST_IN_OUT" -> {
                boolean hit = agg.fastIn() >= threshold && agg.fastIn() > 0
                        && agg.fastOut() * 100 >= agg.fastIn() * ratio;
                return new Eval(hit, false, null, null,
                        "窗口入账 " + agg.fastIn() + " / 出账 " + agg.fastOut() + "（阈值 " + threshold
                                + "，出/入 ≥ " + ratio + "%）");
            }
            case "ABNORMAL_HOUR" -> {
                boolean hit = agg.nightCount() >= minCount && agg.nightAmount() >= threshold;
                return new Eval(hit, false, null, null,
                        "异常时段笔数 " + agg.nightCount() + " 金额 " + agg.nightAmount()
                                + "（阈值 " + threshold + "）");
            }
            case "CROSS_BORDER" -> {
                boolean hit = agg.crossBorderCount() >= minCount && agg.crossBorderAmount() >= threshold;
                return new Eval(hit, false, null, null,
                        "跨境笔数 " + agg.crossBorderCount() + " 金额 " + agg.crossBorderAmount()
                                + "（阈值 " + threshold + "；未知归属地不算跨境）");
            }
            case "SANCTION", "PEP" -> {
                AmlWatchlistService.Hit h = watchlist.screen(type, ctx.getPayerId());
                return new Eval(h.hit(), false, null, null,
                        type + " 名单筛查 payer=" + AmlWatchlistService.mask(ctx.getPayerId())
                                + " hit=" + h.hit() + " match=" + h.matchType());
            }
            default -> {
                return Eval.miss("未知规则类型或缺少 conditionExpr: " + type);
            }
        }
    }
}