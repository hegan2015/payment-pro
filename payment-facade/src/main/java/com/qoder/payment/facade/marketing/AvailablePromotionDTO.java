package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * X-3a/X-3b 可用优惠聚合（<b>只读，不核销</b>）。
 *
 * <p><b>与 C-4 的边界</b>：本 DTO 只回答「有哪些可用」，<b>不做最优组合推荐</b>——
 * 组合枚举/互斥规则/最大抵扣属后续 C-4（payment-cashier PromotionCalcService），本期不实现。
 */
@Data
public class AvailablePromotionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private Long orderAmount;
    private List<CouponDTO> coupons;
    private List<RedPacketDTO> redPackets;
    private List<ActivityDTO> activities;
    /** 积分账户余额（虚拟权益单位，非金额）。 */
    private Long pointsBalance;
    /** 可用积分 = balance − frozen。 */
    private Long pointsAvailable;
    /** 积分可抵扣金额（分）= pointsAvailable / 兑换率（DynamicConfig 可配）。 */
    private Long pointsAmount;
    /** 券+红包面额合计（分，不含积分/活动，仅供展示）。 */
    private Long voucherAmount;
}