package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RefundResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private PaymentDTO payment;
    private Long refundAmount;
    private String refundId;
}