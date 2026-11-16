package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private String subject;
    private Long amount;
}