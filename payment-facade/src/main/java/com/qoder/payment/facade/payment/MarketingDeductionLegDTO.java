package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * P-7 营销抵扣明细行（<b>落库可追溯</b>，payment 域 {@code payment_marketing_leg}）。
 *
 * <p>一行 = 一次营销核销动作，携带营销域核销单 id 与<b>资金凭证号</b>（{@code MKT:}/{@code MKTR:}），
 * 与 accounting 分录 1:1 可回查；退回后状态迁 {@code ROLLED_BACK} 并保留原凭证号（只追加语义）。
 */
@Data
public class MarketingDeductionLegDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String paymentId;
    /** 编排顺序号（券→红包→积分→活动，从 1 起）。 */
    private Integer legSeq;
    /** COUPON / RED_PACKET / POINTS / ACTIVITY。 */
    private String source;
    /** 券 id / 红包 id / 活动 id；POINTS 为 memberId。 */
    private String refId;
    /** 营销域核销单 id（券/红包/活动）或积分流水 id。 */
    private String useId;
    /** 营销资金凭证号（MKT:…；退回凭证 MKTR:… 由营销域确定性派生）。 */
    private String bizNo;
    /** 营销侧幂等键（POINTS 为 points_log.biz_no，退回按此键）。 */
    private String bizKey;
    /** 抵扣额（分）。 */
    private Long amount;
    /** 消耗积分数（仅 POINTS）。 */
    private Long points;
    /** PENDING / USED / ROLLED_BACK / FAILED。 */
    private String status;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
}