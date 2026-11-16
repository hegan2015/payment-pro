package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String method;
    private String accountId;
    private String cardNo;
    private String requestId;
    private String callbackUrl;
}