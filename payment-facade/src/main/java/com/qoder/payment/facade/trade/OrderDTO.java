package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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