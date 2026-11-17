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
}