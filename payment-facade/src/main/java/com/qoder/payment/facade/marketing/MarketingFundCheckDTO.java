package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a/X-3b 营销资金恒等核对（<b>只读</b>）：营销域自有核销账 vs 账务域 SYS_MARKETING 真实分录。
 *
 * <p>恒等式：{@code netUse − netRefund == sysMarketingOutflow == −sysMarketingBalance}，
 * 且账务试算 {@code totalDebit == totalCredit}、{@code balanceSum == 0}。
 * SYS_MARKETING 出账只统计 bizNo 以 {@code MKT:} 开头的分录，不受其它泳道影响。
 */
@Data
public class MarketingFundCheckDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 营销域累计核销总额（gross，含后来被退回的核销；券+红包+活动+积分抵扣，分）。 */
    private Long netUse;
    /** 营销域累计退回总额（分）。 */
    private Long netRefund;
    /** netUse − netRefund。 */
    private Long netMarketing;
    /** 账务域 SYS_MARKETING 借方累计（MKT: 凭证，分）= 营销出账。 */
    private Long sysMarketingDebit;
    /** 账务域 SYS_MARKETING 贷方累计（MKT: 凭证，分）= 营销退回。 */
    private Long sysMarketingCredit;
    /** sysMarketingDebit − sysMarketingCredit。 */
    private Long sysMarketingOutflow;
    /** SYS_MARKETING 账户余额（credit−debit，营销支出为负）。 */
    private Long sysMarketingBalance;
    /** netMarketing − sysMarketingOutflow，守恒要求 0。 */
    private Long diff;
    private Boolean balanced;
    private Long trialDebit;
    private Long trialCredit;
    private Boolean trialBalanced;
    private Long balanceSum;
    private Integer accounts;
    private Long entries;
    private String message;
}