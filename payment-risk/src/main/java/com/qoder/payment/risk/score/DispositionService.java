package com.qoder.payment.risk.score;

import com.qoder.payment.facade.risk.RiskAction;
import com.qoder.payment.facade.risk.RiskThresholdDTO;
import com.qoder.payment.risk.config.RiskConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * R-4 分级处置：分数 → 放行 / 加强验证 / 限额 / 拒绝 / 转人工。
 *
 * <pre>
 * score &lt; challenge                 → PASS          放行
 * challenge ≤ score &lt; limit         → CHALLENGE     加强验证（C-3 收银台增强认证）
 * limit     ≤ score &lt; reject        → LIMIT         降额放行；amount &gt; limitAmount 则拒绝
 * reject    ≤ score &lt; review        → REJECT        拒绝
 * score ≥ review                    → MANUAL_REVIEW 转人工（DisputeFacade 建单）
 * </pre>
 *
 * <p>规则动作与分档动作取<b>更严</b>者（{@link RiskAction#severity}）。
 * <p><b>payment 域收敛</b>：非 PASS 一律 {@code pass=false}（受理前拒绝，不留 PROCESSING 悬挂单）；
 * action 字段保留细分语义供控制台与后续 C-3 泳道续付使用。
 */
@Slf4j
@Service
public class DispositionService {

    /** 处置结论。 */
    public record Disposition(String action, boolean pass, String reason, String code, RiskThresholdDTO thresholds) {
    }

    private final RiskConfigManager configs;

    public DispositionService(RiskConfigManager configs) {
        this.configs = configs;
    }

    public Disposition decide(String scene, int score, long amount, String ruleAction) {
        RiskThresholdDTO t = configs.thresholds(scene);
        String banded = band(t, score);
        String action = RiskAction.severity(ruleAction) > RiskAction.severity(banded) ? ruleAction : banded;
        if (action == null) {
            action = RiskAction.PASS;
        }
        String reason = null;
        boolean pass = true;
        switch (action) {
            case RiskAction.CHALLENGE -> {
                pass = false;
                reason = "风险评分 " + score + " 需加强验证（阈值 " + t.getChallengeScore() + "）";
            }
            case RiskAction.LIMIT -> {
                if (amount > t.getLimitAmount()) {
                    action = RiskAction.REJECT;
                    pass = false;
                    reason = "风险评分 " + score + " 触发降额限额，本笔 " + amount + " 超出降额上限 "
                            + t.getLimitAmount() + " 分";
                } else {
                    reason = "风险评分 " + score + " 降额放行（上限 " + t.getLimitAmount() + " 分）";
                }
            }
            case RiskAction.REJECT -> {
                pass = false;
                reason = "风险评分 " + score + " 达到拒绝阈值 " + t.getRejectScore();
            }
            case RiskAction.MANUAL_REVIEW -> {
                pass = false;
                reason = "风险评分 " + score + " 达到人审阈值 " + t.getReviewScore() + "，转人工复核";
            }
            default -> action = RiskAction.PASS;
        }
        return new Disposition(action, pass, reason, RiskAction.CODE_SCORE, t);
    }

    private static String band(RiskThresholdDTO t, int score) {
        if (score >= t.getReviewScore()) {
            return RiskAction.MANUAL_REVIEW;
        }
        if (score >= t.getRejectScore()) {
            return RiskAction.REJECT;
        }
        if (score >= t.getLimitScore()) {
            return RiskAction.LIMIT;
        }
        if (score >= t.getChallengeScore()) {
            return RiskAction.CHALLENGE;
        }
        return RiskAction.PASS;
    }
}