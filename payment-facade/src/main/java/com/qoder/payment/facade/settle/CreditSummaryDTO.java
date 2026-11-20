package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 授信/垫资守恒视图：
 * {@code credit.used == Σ未回收垫资 == −SYS_ADVANCE 账务余额}（误差 0）。
 * SYS_ADVANCE 是平台垫资应收（资产语义，借增 ⇒ 余额为负），与 SYS_CASH 同口径。
 */
@Data
public class CreditSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private Long creditLimit;
    private Long used;
    private Long available;
    private String status;
    /** Σ垫资额。 */
    private Long advancedAmount;
    /** Σ追偿额。 */
    private Long recoveredAmount;
    /** 未回收 = advanced − recovered。 */
    private Long outstanding;
    /** −balance(SYS_ADVANCE)（全局口径）。 */
    private Long sysAdvanceOutstanding;
    /** 全局未回收垫资合计。 */
    private Long globalOutstanding;
    /** used == outstanding（商户级）。 */
    private Boolean usedMatched;
    /** sysAdvanceOutstanding == globalOutstanding（全局恒等式）。 */
    private Boolean balanced;
    private Long diff;
}