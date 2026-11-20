package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 审计写入请求（G-1）：who/when/what/结果/IP/traceId，只追加不可篡改。 */
@Data
public class AuditWriteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String username;
    /** 动作：HTTP 方法或 LOGIN / LOGIN_FAIL / LOGOUT / REFRESH 等语义动作。 */
    private String action;
    private String method;
    private String path;
    /** 关键业务单号（bizNo/requestId/outTradeNo/路径主键），可空。 */
    private String bizNo;
    /** 摘要（查询串等），严禁携带口令/密钥明文。 */
    private String detail;
    /** HTTP 状态码或结果标记。 */
    private String result;
    private String ip;
    private String traceId;
}