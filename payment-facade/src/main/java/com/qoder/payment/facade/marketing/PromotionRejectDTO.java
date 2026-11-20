package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-4 落选项（含<b>落选原因码</b>，可解释、可复核）。
 *
 * <p>原因码：{@code THRESHOLD_NOT_MET} 未达满减门槛 / {@code EXPIRED} 已过期 /
 * {@code STATUS_INVALID} 状态不可用 / {@code BUDGET_INSUFFICIENT} 活动预算余额不足 /
 * {@code MUTEX} 与选中来源互斥 / {@code STACK_LIMIT} 超出叠加上限 /
 * {@code CAP_REACHED} 抵扣已达订单额上限 / {@code SMALLER_VALUE} 同组面额更小未入选 /
 * {@code POINTS_ZERO} 可用积分不足抵 1 分 / {@code DISABLED} 规则关闭该来源 /
 * {@code PRUNED} 候选爆炸无损剪枝 / {@code ZERO_DISCOUNT} 立减额为 0。
 */
@Data
public class PromotionRejectDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;
    private String refId;
    /** 若不受约束本可抵扣的金额（分），便于用户理解「差多少」。 */
    private Long potentialAmount;
    private String code;
    private String reason;
}