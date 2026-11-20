package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** G-4a 配额视图（DB 计费计数 + 策略上限；网关另补实时 QPS 窗口计数）。 */
@Data
public class ApiQuotaDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    private Boolean registered;
    private String status;
    private Boolean sandbox;
    private List<String> scopes;
    private Integer rateLimit;
    private Long dailyQuota;
    private Long unitPrice;
    /** 计费账本当日已计次（openapi_call_log 汇总）。 */
    private Long usedToday;
    /** 当日已拒绝次数（限流/配额/scope/撤销）。 */
    private Long rejectedToday;
    /** 剩余配额；dailyQuota=0 → null 表示不限。 */
    private Long remaining;
    private String bizDate;
    /** 网关填充：当前秒窗口已用 QPS。 */
    private Integer qpsUsed;
}