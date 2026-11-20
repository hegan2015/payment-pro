package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * C-4 最优组合规则（叠加/互斥/封顶），<b>全部确定性整数口径</b>。
 *
 * <p>缺省值由营销域 {@code DynamicConfig}（I-2：local 文件 / docker Apollo）提供，
 * 请求显式传值优先；生效规则会在 {@link BestPromotionDTO#getRules()} 原样回显，便于解释与复算。
 *
 * <p><b>互斥语义</b>：{@code mutex} 为「来源组」级互斥对，形如 {@code "COUPON|ACTIVITY"}
 * （券与活动不可同单叠加）。组内叠加由 {@code maxXxx} 张数上限表达（1=不可叠加）。
 * 积分默认可与任意来源叠加（{@code pointsStackable=true}），置 false 则积分与券/红包/活动互斥。
 *
 * <p><b>资金红线</b>：{@code maxDiscountBps} 是总抵扣占订单额的万分比上限（默认 10000=100%），
 * 任何组合都不得令实付为负；活动预算余额不足者一律落选，绝不为凑最优超预算。
 */
@Data
public class PromotionRulesDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 单笔最多用券张数（默认 1=不可叠加）。 */
    private Integer maxCoupons;
    /** 单笔最多用红包个数（默认 1）。 */
    private Integer maxRedPackets;
    /** 单笔最多命中活动数（默认 1）。 */
    private Integer maxActivities;
    /** 积分是否可与券/红包/活动叠加（默认 true）。 */
    private Boolean pointsStackable;
    /** 来源组互斥对，形如 COUPON|ACTIVITY（顺序无关，服务端归一化）。 */
    private List<String> mutex;
    /** 总抵扣上限（占订单额万分比，默认 10000=100%，即实付 ≥ 0）。 */
    private Integer maxDiscountBps;
}