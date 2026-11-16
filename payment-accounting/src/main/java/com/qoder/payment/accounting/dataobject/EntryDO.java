package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class EntryDO {

    private String id;
    private Long seq;
    private String bizNo;
    private String accountId;
    private String dir;
    private Long amount;
    private Long balanceAfter;
    private String subject;
    private String refId;
    private Long createdAt;
}