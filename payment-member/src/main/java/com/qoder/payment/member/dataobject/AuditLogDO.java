package com.qoder.payment.member.dataobject;

import lombok.Data;

/** 操作审计（G-1），只追加。 */
@Data
public class AuditLogDO {

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