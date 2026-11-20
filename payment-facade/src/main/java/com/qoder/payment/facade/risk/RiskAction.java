package com.qoder.payment.facade.risk;

/** R-4 分级处置动作 + R-2/R-3 拒绝错误码（规范化，上游可分类统计）。 */
public final class RiskAction {

    /** 放行。 */
    public static final String PASS = "PASS";
    /** 加强验证（收银台增强认证 C-3）。 */
    public static final String CHALLENGE = "CHALLENGE";
    /** 降额放行（超过降额阈值则拒绝）。 */
    public static final String LIMIT = "LIMIT";
    /** 拒绝。 */
    public static final String REJECT = "REJECT";
    /** 转人工（差错平台建单）。 */
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    /** 单笔限额（内置保守规则，口径与 P0 一致）。 */
    public static final String CODE_SINGLE_LIMIT = "RISK_SINGLE_LIMIT";
    /** 频率限额（内置保守规则，口径与 P0 一致）。 */
    public static final String CODE_RATE_LIMIT = "RISK_RATE_LIMIT";
    /** 多维限额（R-2）。 */
    public static final String CODE_QUOTA = "RISK_QUOTA_EXCEEDED";
    /** 名单（R-3）。 */
    public static final String CODE_LIST = "RISK_LIST_HIT";
    /** 规则引擎（R-1）。 */
    public static final String CODE_RULE = "RISK_RULE_HIT";
    /** 评分分级（R-4）。 */
    public static final String CODE_SCORE = "RISK_SCORE";

    private RiskAction() {
    }

    /** 处置严重度排序（取最严处置）。 */
    public static int severity(String action) {
        if (action == null) {
            return 0;
        }
        return switch (action) {
            case CHALLENGE -> 1;
            case LIMIT -> 2;
            case REJECT -> 3;
            case MANUAL_REVIEW -> 4;
            default -> 0;
        };
    }
}