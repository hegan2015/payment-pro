package com.qoder.payment.risk.engine;

import com.qoder.payment.facade.risk.RiskRuleDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * R-1 灰度路由：按 payerId 稳定哈希取模判定规则是否对该付款方生效。
 *
 * <p>白名单（逗号分隔 payerId）恒生效，不受比例限制——便于「先对自己账号灰度」。
 * 同一 payerId 在同一比例下判定稳定（不会同一用户忽而生效忽而不生效）。
 * grayPercent 缺省 100（全量）；&le;0 表示不生效（等价停用）。
 */
@Slf4j
public final class RuleGrayRouter {

    private RuleGrayRouter() {
    }

    public static boolean applies(RiskRuleDTO rule, String payerId) {
        String whitelist = rule.getWhitelist();
        if (whitelist != null && !whitelist.isBlank() && payerId != null) {
            for (String w : whitelist.split(",")) {
                if (w.trim().equals(payerId)) {
                    return true;
                }
            }
        }
        int percent = rule.getGrayPercent() == null ? 100 : rule.getGrayPercent();
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key = payerId == null ? "" : payerId;
        return Math.floorMod(stableHash(key), 100) < percent;
    }

    /** 稳定哈希（不依赖 String.hashCode 的 JVM 版本稳定性假设，用 FNV-1a 32bit）。 */
    static int stableHash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    /** 场景匹配：规则 scene 为空/ALL 视为全场景。 */
    public static boolean sceneMatches(RiskRuleDTO rule, String scene) {
        String rs = rule.getScene();
        return rs == null || rs.isBlank() || "ALL".equalsIgnoreCase(rs) || rs.equalsIgnoreCase(scene);
    }

    /** 规则是否处于可执行状态（PUBLISHED；DRAFT/DISABLED 不执行）。 */
    public static boolean executable(RiskRuleDTO rule) {
        String st = rule.getStatus();
        return st == null || "PUBLISHED".equalsIgnoreCase(st);
    }

    public static void sort(List<RiskRuleDTO> rules) {
        rules.sort((a, b) -> Integer.compare(a.getPriority() == null ? 100 : a.getPriority(),
                b.getPriority() == null ? 100 : b.getPriority()));
    }
}