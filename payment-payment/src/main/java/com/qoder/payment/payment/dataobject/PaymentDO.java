package com.qoder.payment.payment.dataobject;

import lombok.Data;

@Data
public class PaymentDO {

    private String id;
    private String orderId;
    private String merchantId;
    private String bizType;
    private String method;
    private String payerAccountId;
    private String cardNo;
    private String merchantAccountId;
    private Long amount;
    private Long fee;
    private String status;
    private Long refundedAmount;
    private Long refundedFee;
    private String channelBizNo;
    private String failReason;
    private String requestId;
    private String xid;
    private String callbackUrl;
    private Long createdAt;
    /** 补单次数（S-5 留痕，默认 0）。 */
    private Integer reconcileCount;
    /** 最近一次补单时间（毫秒，S-5 留痕）。 */
    private Long lastReconcileAt;

    // ---------- P1 I 泳道 additive ----------
    /** P-2 SINGLE（默认）/ COMBO。 */
    private String payMode;
    /** P-6 路由选中渠道（CARD）。 */
    private String channelCode;
    /** P-5 CLOSE / CANCEL。 */
    private String closeType;
    private Long closedAt;

    // ---------- P2 E 泳道 additive（P-7 营销抵扣编排；既有字段语义/fee 口径不变） ----------
    /** 营销净抵扣额（分，默认 0；退回后收敛为剩余净额）。amount 仍是订单全额=fee 基数。 */
    private Long marketingAmount;
    /** NONE（默认）/ APPLIED / ROLLED_BACK / ROLLBACK_PENDING。 */
    private String marketingStatus;

    // ---------- P2 H 泳道 additive（T-5 合并/分阶段；既有字段语义一字未改） ----------
    /** T-5 分阶段期序（仅 STAGE 单，1 起；其余 NULL）。 */
    private Integer stageSeq;
    /** T-5 所属合并单 id（仅 MERGE/MERGE_COMBO 单；其余 NULL）。 */
    private String mergeId;
}