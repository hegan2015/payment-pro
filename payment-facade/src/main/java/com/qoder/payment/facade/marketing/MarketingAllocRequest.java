package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * X-3b 营销抵扣分摊请求（订单/商品/商户维度）。
 *
 * <p><b>与 P-7 的边界</b>：本请求只做「给定抵扣总额 → 按规则拆到各目标并校验守恒」，
 * <b>不编排支付腿、不动账、不改 payment</b>。P-7 抵扣编排后续由 payment 域调用
 * {@code MarketingFacade.useCoupon/usePoints/applyActivity} + {@code allocate} 组合完成。
 *
 * <p>幂等键 {@code (MARKETING_ALLOC, paymentId:requestId)}；同键重放返回既有分摊结果。
 */
@Data
public class MarketingAllocRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String requestId;
    /** 待分摊的营销抵扣总额（分）。缺省取该 paymentId 的 SOURCE 维度净额（Σ核销−Σ退回）。 */
    private Long totalAmount;
    /** WEIGHT（缺省，按权重最大余数法）/ EXPLICIT（显式金额，Σ 必须等于总额）。 */
    private String mode;
    private List<AllocTargetDTO> targets;
    private String operator;
}