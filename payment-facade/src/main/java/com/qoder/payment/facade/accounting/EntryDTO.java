package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class EntryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String bizNo;
    private String accountId;
    private String dir;
    private Long amount;
    private Long balanceAfter;
    private String subject;
    private String refId;
    private Long createdAt;
}