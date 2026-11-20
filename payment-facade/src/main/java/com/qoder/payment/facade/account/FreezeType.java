package com.qoder.payment.facade.account;

/**
 * A-3 账户状态冻结类型（状态级闸门，账务域 checkAccounts 对非 ACTIVE 全方向拒绝）。
 * <ul>
 *   <li>NONE：正常；</li>
 *   <li>FULL：全额冻结（兼容既有 FROZEN 语义，借贷全拒）；</li>
 *   <li>JUDICIAL：司法冻结（全拒 + 解冻需 authorizedBy 双人复核）。</li>
 * </ul>
 * 方向性 CREDIT_ONLY/DEBIT_ONLY 需账务域按借贷方向分别校验（checkAccounts 现仅认 ACTIVE），
 * 属 payment-accounting 接线项，本期不开放（避免「标记冻结但实际可动账」的假安全）。
 * 只收不付的存量资金管控可用 {@link FreezeReason#RISK} 金额冻结（frozen=available）实现。
 */
public enum FreezeType {

    NONE,
    FULL,
    JUDICIAL;

    public static FreezeType of(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.trim().toUpperCase());
    }
}