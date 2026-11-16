package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class NotifyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String event;
    private String bizId;
    private Map<String, Object> payload;
    private String callbackUrl;
}