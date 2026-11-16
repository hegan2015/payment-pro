package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SettleRefundRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizId;
    private String paymentId;
    private Long amount;
    private Long feeRefund;
}