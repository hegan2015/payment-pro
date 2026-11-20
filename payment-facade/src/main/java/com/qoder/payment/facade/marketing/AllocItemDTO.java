package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 分摊明细行（只追加）。
 *
 * <p>{@code dimension=SOURCE}：营销来源维度（COUPON/RED_PACKET/POINTS/ACTIVITY），由各核销动作自动落行；
 * {@code dimension=TARGET}：订单/商品/商户维度，由 {@code allocate} 落行。
 * 两个维度各自 Σ(USE) − Σ(REFUND) 必须相等且等于营销抵扣总额（守恒，误差 0）。
 */
@Data
public class AllocItemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String paymentId;
    /** SOURCE / TARGET。 */
    private String dimension;
    /** SOURCE 维度：COUPON/RED_PACKET/POINTS/ACTIVITY；TARGET 维度：ORDER/ITEM/MERCHANT。 */
    private String source;
    /** SOURCE 维度=券/红包/积分流水/活动核销单 id；TARGET 维度=订单/商品/商户 id。 */
    private String targetId;
    private Long amount;
    /** USE / REFUND。 */
    private String type;
    private String bizNo;
    private Long createdAt;
}