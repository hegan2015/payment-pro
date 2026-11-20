package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 分摊目标（订单/商品/商户维度）。
 *
 * <p>WEIGHT 模式按 {@code weight} 占比分摊（最大余数法，Σ 恒等于总额，误差 0）；
 * EXPLICIT 模式直接用 {@code amount}（服务端强校验 Σamount == totalAmount，不等即拒绝，绝不动账）。
 */
@Data
public class AllocTargetDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ORDER / ITEM / MERCHANT。 */
    private String targetType;
    private String targetId;
    /** 权重（正整数；WEIGHT 模式必填）。 */
    private Long weight;
    /** 显式金额（分；EXPLICIT 模式必填）。 */
    private Long amount;
}