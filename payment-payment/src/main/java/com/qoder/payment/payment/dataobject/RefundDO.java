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
}