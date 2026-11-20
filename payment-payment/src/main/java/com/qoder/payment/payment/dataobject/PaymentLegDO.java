package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** P-2 组合支付腿（payment_leg，单库不分片；每腿账务 bizNo=legId）。 */
@Data
public class PaymentLegDO {

    private String id;
    private String paymentId;
    private Integer legSeq;
    private String method;
    private String payerAccountId;
    private String cardNo;
    private Long amount;
    private Long fee;
    /** PENDING/SUCCESS/FAILED。 */
    private String status;
    private String channelBizNo;
    private String channelCode;
    private Long refundedAmount;
    private Long refundedFee;
    private Long createdAt;
}