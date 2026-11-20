package com.qoder.payment.member.openplatform;

import lombok.Data;

/** G-4a 调用计费日聚合（openapi_call_log，UNIQUE(app_id,api,biz_date)）。与资金账无关。 */
@Data
public class ApiCallLogDO {

    private String id;
    private String appId;
    private String api;
    private String bizDate;
    private Long callCount;
    private Long rejectCount;
    private Long unitPrice;
    private Long billedAmount;
    private Long updatedAt;
}