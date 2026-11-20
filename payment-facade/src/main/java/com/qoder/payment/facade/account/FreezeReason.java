package com.qoder.payment.facade.account;

/**
 * A-3 冻结原因细分：行政冻结按原因建独立冻结单，解冻必须对应原因。
 * 金额冻结经账务 TCC 参与者 Try/Cancel 落地（frozen 列），不产生分录，试算平衡不受影响。
 */
public enum FreezeReason {

    /** 风控冻结。 */
    RISK,
    /** 司法冻结（解冻需双人复核：authorizedBy ≠ operator）。 */
    JUDICIAL,
    /** 预授权冻结。 */
    PREAUTH,
    /** 争议冻结（差错/争议处理期间）。 */
    DISPUTE;

    public static FreezeReason of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reasonType 必填（RISK/JUDICIAL/PREAUTH/DISPUTE）");
        }
        return valueOf(value.trim().toUpperCase());
    }
}