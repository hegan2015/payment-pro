package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** G-4a 应用授权/配额/计费策略调整（null 字段不改）。 */
@Data
public class OpenAppUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    private String name;
    private List<String> scopes;
    private Integer rateLimit;
    private Long dailyQuota;
    private Long unitPrice;
    private String apiVersion;
    private String operator;
}