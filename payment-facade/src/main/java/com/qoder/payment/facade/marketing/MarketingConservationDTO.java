package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 守恒校验报告（<b>只读，不动账</b>）：供 P-7 抵扣编排在受理/完结时做强校验。
 *
 * <p>恒等式：{@code orderAmount = fundAmount + marketingAmount}（订单额 = 资金腿 + 营销腿），
 * 且 {@code marketingAmount = Σ来源核销净额 = Σ目标分摊净额}，三项 diff 全 0 才 balanced。
 *
 * <p><b>手续费口径不变</b>：本校验只覆盖营销抵扣额，gross/fee/net 仍由 settle FeeCalculator
 * 按 feeBps→SYS_FEE 计算，营销域不参与、不改写手续费。
 */
@Data
public class MarketingConservationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private Long orderAmount;
    private Long fundAmount;
    private Long marketingAmount;
    private Long sourceSum;
    private Long targetSum;
    /** orderAmount − fundAmount − marketingAmount。 */
    private Long orderDiff;
    /** sourceSum − marketingAmount。 */
    private Long sourceDiff;
    /** targetSum − marketingAmount。 */
    private Long targetDiff;
    private Boolean balanced;
    private String message;
}