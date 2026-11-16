package com.qoder.payment.payment.dataobject;

import lombok.Data;

@Data
public class OutboxDO {

    private Long id;
    private String event;
    private String bizId;
    private String payload;
    private String status;
    private Integer attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
}