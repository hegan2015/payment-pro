package com.qoder.payment.facade.payment;

import com.qoder.payment.facade.marketing.MarketingAllocDTO;
import com.qoder.payment.facade.marketing.MarketingConservationDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * P-7 营销抵扣编排结果（只读视图，<b>抵扣明细 + 分摊 + 守恒三件套可追溯</b>）。
 *
 * <p>恒等式（全部 diff=0 才算守恒）：
 * <pre>
 *   orderAmount = payAmount + netAmount          （订单额 = 净实付 + 营销净抵扣）
 *   netAmount   = appliedAmount − refundedAmount （营销净抵扣 = Σ核销 − Σ退回）
 *   netAmount   = Σ来源分摊净额 = Σ目标分摊净额   （allocation.diff = 0）
 * </pre>
 *
 * <p><b>手续费口径不变</b>：{@code fee} 仍是订单全额 × feeBps（settle FeeCalculator 受理快照），
 * 营销抵扣<b>不改 fee 基数</b>；{@code payAmount} 只表达用户净现金流出。
 */
@Data
public class MarketingDeductionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String orderId;
    private String memberId;
    /** 抵扣入账（=付款 BALANCE）户。 */
    private String accountId;
    /** 订单金额（分，= payment.amount，fee 基数）。 */
    private Long orderAmount;
    /** 手续费（分，受理快照，营销抵扣不改口径）。 */
    private Long fee;
    /** Σ核销额（分，历史累计，含后来退回的）。 */
    private Long appliedAmount;
    /** Σ退回额（分）。 */
    private Long refundedAmount;
    /** 营销净抵扣 = appliedAmount − refundedAmount（= 营销域来源净额）。 */
    private Long netAmount;
    /** 净实付 = orderAmount − netAmount，恒 ≥ 0。 */
    private Long payAmount;
    /** NONE / APPLIED / ROLLED_BACK / ROLLBACK_PENDING。 */
    private String status;
    /** 编排明细（券→红包→积分→活动顺序）。 */
    private List<MarketingDeductionLegDTO> legs;
    /** 营销域分摊（来源 + 目标双维度，diff 恒 0 才守恒）。 */
    private MarketingAllocDTO allocation;
    /** 营销域守恒校验（orderAmount = payAmount + netAmount）。 */
    private MarketingConservationDTO conservation;
    private String requestId;
    private Long createdAt;
    private Long updatedAt;
}