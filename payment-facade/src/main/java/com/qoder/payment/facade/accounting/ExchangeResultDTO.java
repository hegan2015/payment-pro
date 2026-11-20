package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-4 换汇结果 + 汇率快照落库回执。
 *
 * <p><b>跨币种平衡机制</b>（AC-6）：一次换汇 = <b>两张同币种凭证</b>，各自账套内 D=C：
 * <pre>
 * from 凭证（fromCurrency）: D fromAccount (amount+fee)   C SYS_CASH[from] amount   C SYS_FEE[from] fee
 * to   凭证（toCurrency）  : D SYS_CASH[to] grossTo       C toAccount netTo         C SYS_FX[to] fxGain
 * </pre>
 * 其中 {@code grossTo = amount × midRate / scale}（账面中间价折算），
 * {@code netTo = amount × buyRate / scale}（客户实收），{@code fxGain = grossTo − netTo}（汇兑损益，可为负=损失）。
 * 每个币种账套内 Σbalance 恒为 0；<b>不同币种金额从不直接相加</b>。
 * 手续费沿用既有口径入 {@code SYS_FEE}（6001），不新增系统户与科目映射。
 */
@Data
public class ExchangeResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String fromBizNo;
    private String toBizNo;
    private String fromAccountId;
    private String toAccountId;
    private String fromCurrency;
    private String toCurrency;
    /** 卖出额（from 币种，分）。 */
    private Long amount;
    /** 换汇手续费（from 币种，分）。 */
    private Long fee;
    private Integer feeBps;
    /** 中间价折算额（to 币种，分）= 账面价值。 */
    private Long grossToAmount;
    /** 客户实收额（to 币种，分）。 */
    private Long netToAmount;
    /** 汇兑损益（to 币种，分）= grossTo − netTo，落 SYS_FX[to]。 */
    private Long fxGain;
    /** 汇兑损益户（to 币种账套）。 */
    private String fxGainAccount;
    /** 手续费户（from 币种账套）。 */
    private String feeAccount;
    private Long buyRate;
    private Long sellRate;
    private Long midRate;
    private Integer rateScale;
    private Long rateVersion;
    private String rateSource;
    private Long rateEffectiveAt;
    private Long fromBalanceAfter;
    private Long toBalanceAfter;
    private Long createdAt;
}