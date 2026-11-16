package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class TransferDO {

    private String bizNo;
    private String lines;
    private String subject;
    private String refId;
    private Boolean reversed;
    private Long createdAt;
}