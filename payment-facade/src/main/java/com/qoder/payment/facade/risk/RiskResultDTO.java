package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RiskResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean pass;
    private String reason;
}