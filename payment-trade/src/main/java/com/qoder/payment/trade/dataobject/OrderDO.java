package com.qoder.payment.trade.dataobject;

import lombok.Data;

@Data
public class OrderDO {

    private String id;
    private String merchantId;
    private String merchantName;
    private String outTradeNo;
    private String bizType;
    private String subject;
    private Long amount;
    private String status;
    private String paymentId;
    private Long refundedAmount;
    private Long createdAt;
    private Long paidAt;
    private Long closedAt;
}