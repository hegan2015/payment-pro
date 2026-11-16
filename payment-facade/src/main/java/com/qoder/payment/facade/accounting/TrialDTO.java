package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TrialDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long totalDebit;
    private Long totalCredit;
    private Boolean balanced;
    private Long balanceSum;
    private Integer accounts;
    private Integer entries;
}