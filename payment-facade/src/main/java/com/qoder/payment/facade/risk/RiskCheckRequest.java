package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RiskCheckRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String payerId;
    private String merchantId;
    private Long amount;
    private String method;
}