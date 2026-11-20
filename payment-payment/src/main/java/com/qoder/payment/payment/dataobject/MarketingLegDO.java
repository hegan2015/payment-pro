package com.qoder.payment.payment.dataobject;

import lombok.Data;

/**
 * P-7 营销抵扣明细行（{@code payment_marketing_leg}，单库不分片，只追加 + 状态 CAS）。
 *
 * <p>一行 = 一次营销核销动作，携带营销域核销单 id 与资金凭证号（与 accounting 分录 1:1 可回查）。
 * UNIQUE(payment_id,leg_seq) + UNIQUE(payment_id,source,ref_id) 双重防重 ⇒ 编排重放不产生重复抵扣行。
 */
@Data
public class MarketingLegDO {

    private String id;
    private String paymentId;
    private Integer legSeq;
    /** COUPON / RED_PACKET / POINTS / ACTIVITY。 */
    private String source;
    private String refId;
    private String useId;
    /** 营销资金凭证号 MKT:…（退回凭证 MKTR:… 由营销域确定性派生）。 */
    private String bizNo;
    /** 营销侧幂等键（POINTS 为 points_log.biz_no）。 */
    private String bizKey;
    private Long amount;
    private Long points;
    /** PENDING / USED / ROLLED_BACK / FAILED。 */
    private String status;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
}