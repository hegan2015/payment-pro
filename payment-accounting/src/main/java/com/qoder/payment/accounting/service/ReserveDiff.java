package com.qoder.payment.accounting.service;

/**
 * AC-5 账实差异分级/长短款判定（S-7 与 AC-5 共用同一口径）。
 *
 * <p>分级阈值经 DynamicConfig 可调：{@code accounting.reserve.warn-threshold}（默认 100 分）、
 * {@code accounting.reserve.critical-threshold}（默认 10000 分）。
 * diff = 账面 − 实际：diff&gt;0 长款 LONG、diff&lt;0 短款 SHORT、0 → NONE。
 */
public final class ReserveDiff {

    public static final String KEY_WARN = "accounting.reserve.warn-threshold";
    public static final String KEY_CRITICAL = "accounting.reserve.critical-threshold";
    public static final String KEY_CHANNELS = "accounting.reserve.channels";
    public static final long DEFAULT_WARN = 100L;
    public static final long DEFAULT_CRITICAL = 10_000L;
    public static final String DEFAULT_CHANNELS = "MOCK_BANK";
    public static final String MOCK_BANK = "MOCK_BANK";

    public static final String NONE = "NONE";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";
    public static final String CRITICAL = "CRITICAL";
    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";

    public static String level(long diff, long warnThreshold, long criticalThreshold) {
        long abs = Math.abs(diff);
        if (abs == 0) {
            return NONE;
        }
        if (abs >= criticalThreshold) {
            return CRITICAL;
        }
        return abs >= warnThreshold ? ERROR : WARN;
    }

    public static String direction(long diff) {
        if (diff == 0) {
            return NONE;
        }
        return diff > 0 ? LONG : SHORT;
    }

    /** 渠道归一：null/空 → MOCK_BANK（P0 存量口径）。 */
    public static String channel(String channelCode) {
        return channelCode == null || channelCode.isBlank() ? MOCK_BANK : channelCode.trim();
    }

    private ReserveDiff() {
    }
}