package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 保证金守恒视图：{@code SYS_DEPOSIT 账务余额 == Σ未释放未扣划保证金}（误差 0）。
 * SYS_DEPOSIT 是负债户（贷增），余额为正即平台代管保证金总额。
 */
@Data
public class DepositSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private Integer recordCount;
    /** Σ缴存额。 */
    private Long depositedAmount;
    /** Σ释放额。 */
    private Long releasedAmount;
    /** Σ扣划额。 */
    private Long deductedAmount;
    /** 未释放未扣划 = deposited − released − deducted。 */
    private Long heldAmount;
    /** SYS_DEPOSIT 账务余额（全局口径；merchantId 非空时仍为全局系统户余额）。 */
    private Long sysDepositBalance;
    /** 全局未释放保证金合计（与 sysDepositBalance 比对）。 */
    private Long globalHeldAmount;
    /** 恒等式成立：sysDepositBalance == globalHeldAmount。 */
    private Boolean balanced;
    private Long diff;
}