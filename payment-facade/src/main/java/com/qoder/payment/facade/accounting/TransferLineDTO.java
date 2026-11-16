package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 复式记账分录行：dir 为 D（借）/C（贷）。 */
@Data
public class TransferLineDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private String dir;
    private Long amount;
}