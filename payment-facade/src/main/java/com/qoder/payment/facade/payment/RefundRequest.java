package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RefundRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long amount;
    private String requestId;
}