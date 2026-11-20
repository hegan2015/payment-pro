package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** G-4a 网关异步批量上报的调用增量（幂等聚合：同 appId+api+bizDate 累加）。 */
@Data
public class ApiCallDelta implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    private String api;
    private String bizDate;
    private Long calls;
    private Long rejects;
    /** 计费单价快照（分/次）：由网关按解析到的策略携带，0=免费。 */
    private Long unitPrice;

    public static ApiCallDelta of(String appId, String api, String bizDate, long calls, long rejects, long unitPrice) {
        ApiCallDelta delta = new ApiCallDelta();
        delta.setAppId(appId);
        delta.setApi(api);
        delta.setBizDate(bizDate);
        delta.setCalls(calls);
        delta.setRejects(rejects);
        delta.setUnitPrice(unitPrice);
        return delta;
    }
}