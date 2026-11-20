package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-6 单币种账套试算结果（additive）。
 *
 * <p><b>口径</b>：{@code balanced} = 本币种内 Σ借方 = Σ贷方；{@code conserved} = 本币种内 Σbalance = 0。
 * 二者同时成立才算该账套平衡——<b>不同币种的金额绝不直接相加</b>，
 * 本位币视图一律「先按快照汇率折算、再相加」。
 */
@Data
public class CurrencyTrialDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String currency;
    private Long totalDebit;
    private Long totalCredit;
    private Long balanceSum;
    /** 本币种内 D = C。 */
    private Boolean balanced;
    /** 本币种内 Σbalance = 0（封闭系统守恒）。 */
    private Boolean conserved;
    private Integer accounts;
    private Integer entries;
    /** 本币种余额绝对值之和（折算前，long 分）。 */
    private Long grossBalance;
    /** 折算用汇率快照（currency → 本位币 CNY，中间价）；CNY 恒 1:1。无可用汇率时为 null。 */
    private Long fxRateToBase;
    private Integer rateScale;
    /** 本位币折算贷方发生额；无汇率时 null（不计入汇总，绝不按 1:1 混算）。 */
    private Long baseCreditTotal;
    /** 本位币折算绝对余额；无汇率时 null。 */
    private Long baseGrossBalance;
}