package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SettlePayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String orderId;
    private String merchantId;
    private Long amount;
    private Long fee;
    private String method;
}