package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class NotifyRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String event;
    private String bizId;
    private Map<String, Object> payload;
    private String callbackUrl;
    private String status;
    private Long attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
}