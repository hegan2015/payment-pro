package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 审计日志条目（G-1）。 */
@Data
public class AuditLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String action;
    private String method;
    private String path;
    private String bizNo;
    private String detail;
    private String result;
    private String ip;
    private String traceId;
    private Long createdAt;
}