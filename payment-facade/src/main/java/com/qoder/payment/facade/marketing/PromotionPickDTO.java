package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-4 选中项（含<b>选中理由</b>与截断留痕，可解释）。
 *
 * <p>{@code amount} 是最终建议抵扣额（分）；{@code originalAmount} 是截断前的原始可抵额。
 * 券/红包/积分可部分使用（截断），活动立减额由规则确定<b>不可截断</b>。
 */
@Data
public class PromotionPickDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** COUPON / RED_PACKET / POINTS / ACTIVITY。 */
    private String source;
    /** 券 id / 红包 id / 活动 id；POINTS 为 memberId。 */
    private String refId;
    /** 名称（券/活动名，便于展示；可空）。 */
    private String name;
    /** 建议抵扣额（分）。 */
    private Long amount;
    /** 截断前原始可抵额（分）。 */
    private Long originalAmount;
    /** 是否因总额上限被截断。 */
    private Boolean truncated;
    /** POINTS 专属：建议消耗积分数（amount × 兑换率）。 */
    private Long points;
    /** 选中理由（确定性文案，含关键约束数值）。 */
    private String reason;
}