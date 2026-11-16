package com.qoder.payment.notify.dataobject;

import lombok.Data;

@Data
public class NotifyRecordDO {

    private String id;
    private String event;
    private String bizId;
    private String payload;
    private String callbackUrl;
    private String status;
    private Long attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
}