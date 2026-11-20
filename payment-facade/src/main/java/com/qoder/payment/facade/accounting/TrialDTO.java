package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TrialDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long totalDebit;
    private Long totalCredit;
    private Boolean balanced;
    private Long balanceSum;
    private Integer accounts;
    private Integer entries;
    /** SYS_SUSPENSE 差错挂账科目余额（S-6，未核销差错差额，可为负）。 */
    private Long suspenseBalance;

    // ==================================================================
    // P2 additive（AC-6 多币种试算）——既有 7 个字段与 CNY 单币种口径一字未改：
    // 顶层仍是 **CNY 账套** 的 ΣD/ΣC/Σbalance/户数/笔数（存量 currency=NULL 按 CNY 读），
    // 单币种现状下与改造前逐值相同；**绝不把不同币种金额直接相加**。
    // 按币种独立平衡见 byCurrency，本位币折算管理视图见 baseCurrency*。
    // ==================================================================

    /** 本位币（固定 CNY）。 */
    private String baseCurrency;
    /** 参与试算的币种账套（CNY 恒在首位）。 */
    private List<String> currencies;
    /** 按币种分账套试算：每币种内 ΣD=ΣC 且 Σbalance=0。 */
    private List<CurrencyTrialDTO> byCurrency;
    /** 所有币种账套均平衡且守恒（AC-6 权威判定）。 */
    private Boolean multiCurrencyBalanced;
    /** 本位币折算贷方发生额汇总 = Σ(各币种 totalCredit × 该币种→CNY 中间价快照)，先折算后相加。 */
    private Long baseCurrencyTotal;
    /** 本位币折算绝对余额汇总 = Σ(各币种 Σ|balance| × 折算率)，管理视图（总头寸规模）。 */
    private Long baseCurrencyGrossBalance;
}