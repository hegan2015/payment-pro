package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PaymentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String orderId;
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
    private Long createdAt;
}