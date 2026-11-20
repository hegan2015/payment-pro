package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * C-4 最优组合计算请求（<b>只读计算，不核销、不动账、不写任何单据</b>）。
 *
 * <p>核销发生在支付受理（P-7 营销抵扣编排），本请求只回答「怎么组合实付最小、为什么」。
 * 同一输入（会员可用权益快照 + 订单额 + 规则）必得同一输出（{@code inputFingerprint} 可复核）。
 */
@Data
public class BestPromotionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 权益归属会员（必填）。 */
    private String memberId;
    /** 订单金额（分，必填 > 0）：满减门槛/比例立减/封顶/抵扣上限均以此为基数。 */
    private Long orderAmount;
    /** 可选订单号（仅用于解释与追溯，不参与计算）。 */
    private String orderId;
    /** 可选抵扣入账户（仅回显，计算不动账）。 */
    private String accountId;
    /** 可选规则覆盖；缺省走 DynamicConfig 默认（生效规则回显于结果）。 */
    private PromotionRulesDTO rules;
    /** 可选：限定券候选（缺省=该会员全部可用券）。 */
    private List<String> couponIds;
    /** 可选：限定红包候选。 */
    private List<String> redPacketIds;
    /** 可选：限定活动候选。 */
    private List<String> activityIds;
    /** 是否参与积分抵扣（默认 true）。 */
    private Boolean usePoints;
    /** 每来源候选上限（剪枝，缺省 200）：超出按面额降序保留前 N，<b>无损剪枝</b>（见算法说明）。 */
    private Integer maxCandidatesPerSource;
    /** 活动子集分支限界节点预算（缺省 20000）：超出标注 exhaustive=false，绝不返回不可行解。 */
    private Integer maxSearchNodes;
    /** 调用方追踪号（不参与幂等，本接口无副作用）。 */
    private String requestId;
}