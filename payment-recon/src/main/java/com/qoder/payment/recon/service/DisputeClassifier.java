package com.qoder.payment.recon.service;

import java.util.Map;

/**
 * RC-1/S-4 差错分类器：差异性质 → 差错类型 → 默认修复策略。
 *
 * <p>分类口径：金额不一致 AMOUNT_MISMATCH（红冲+蓝补）；单边账 ONE_SIDED（挂账待人工核销）；
 * 状态不一致 STATUS_MISMATCH（人工）；手续费差 FEE_MISMATCH（差额挂账）；
 * 渠道缺失 CHANNEL_MISSING（短款挂账，保守不臆断冲正）；通知死信 NOTIFY_DEAD（重放）；
 * 域不可达 SOURCE_UNAVAILABLE（人工，不误报资金差错）。
 */
public final class DisputeClassifier {

    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String ONE_SIDED = "ONE_SIDED";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String FEE_MISMATCH = "FEE_MISMATCH";
    public static final String CHANNEL_MISSING = "CHANNEL_MISSING";
    public static final String NOTIFY_DEAD = "NOTIFY_DEAD";
    public static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";

    public static final String REVERSE_AND_REPOST = "REVERSE_AND_REPOST";
    public static final String SUSPENSE = "SUSPENSE";
    public static final String NOTIFY_REPLAY = "NOTIFY_REPLAY";
    public static final String MANUAL = "MANUAL";

    /** 核对项 → 主差错类型（明细项可逐项细化）。 */
    private static final Map<String, String> CHECK_TYPE = Map.of(
            "账务试算平衡", AMOUNT_MISMATCH,
            "支付单采集", SOURCE_UNAVAILABLE,
            "支付-账务一致", ONE_SIDED,
            "支付-交易一致", STATUS_MISMATCH,
            "支付-清结算一致", ONE_SIDED,
            "支付-渠道一致", CHANNEL_MISSING);

    private DisputeClassifier() {
    }

    public static String typeForCheck(String checkName) {
        return CHECK_TYPE.getOrDefault(checkName, STATUS_MISMATCH);
    }

    /** 类型 → 默认修复策略（资金安全保守：无法判定一律挂账/人工，不强行冲正）。 */
    public static String defaultStrategy(String type) {
        return switch (type == null ? "" : type) {
            case AMOUNT_MISMATCH -> REVERSE_AND_REPOST;
            case ONE_SIDED, CHANNEL_MISSING, FEE_MISMATCH -> SUSPENSE;
            case NOTIFY_DEAD -> NOTIFY_REPLAY;
            default -> MANUAL;
        };
    }

    public static boolean autoFixable(String strategy) {
        return REVERSE_AND_REPOST.equals(strategy) || SUSPENSE.equals(strategy) || NOTIFY_REPLAY.equals(strategy);
    }
}