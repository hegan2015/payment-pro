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
    /** P-4 additive：退款单状态（PROCESSING/SUCCESS/FAILED），异步退款轮询终态用。 */
    private String refundStatus;
}