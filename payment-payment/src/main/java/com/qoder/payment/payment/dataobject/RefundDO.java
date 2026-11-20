package com.qoder.payment.payment.dataobject;

import lombok.Data;

@Data
public class RefundDO {

    private String id;
    private String paymentId;
    private Long amount;
    private Long feeRefund;
    private String requestId;
    private String xid;
    private String status;
    private String bizNo;
    private Long createdAt;

    // ---------- P1 I 泳道 additive ----------
    /** P-4 渠道查询键（CARD 异步退款=refundId；BALANCE 为 null）。 */
    private String channelOrderNo;
    private Integer reconcileCount;
    private Long lastReconcileAt;
    private String failReason;
    /** P-2 组合退款腿分摊 JSON（受理时落库，完结监听据此累加腿已退；单腿为 null）。 */
    private String legAllocJson;
}