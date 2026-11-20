package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 营销活动（立减 DISCOUNT / 满减 FULL_REDUCE）。
 *
 * <p>预算控制：{@code used + amount <= budget} 的<b>原子条件 UPDATE</b>（H2/MySQL 同构），
 * 跨实例并发不超扣；预算为 0 表示不限量。规则匹配只用 threshold/discountBps/discountAmount
 * 三个确定性字段（不引入表达式引擎，避免误配置放大资金风险）。
 */
@Data
public class ActivityDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    /** DISCOUNT（按比例立减）/ FULL_REDUCE（满 threshold 减 discountAmount）。 */
    private String type;
    /** 门槛（分）：订单额 ≥ threshold 才命中。 */
    private Long threshold;
    /** FULL_REDUCE 立减额（分）。 */
    private Long discountAmount;
    /** DISCOUNT 比例（万分比，1000=10%）。 */
    private Integer discountBps;
    /** DISCOUNT 单笔封顶（分），0=不封顶。 */
    private Long maxDiscount;
    /** 预算（分），0=不限量。 */
    private Long budget;
    /** 已用预算（分，原子累加）。 */
    private Long used;
    /** ACTIVE / DISABLED / EXHAUSTED。 */
    private String status;
    private Long expireAt;
    /** 扩展规则 JSON（预留，本期不解释执行）。 */
    private String rule;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}