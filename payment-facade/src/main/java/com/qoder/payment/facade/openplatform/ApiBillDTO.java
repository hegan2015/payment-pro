package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * G-4a 调用计费日账单行（appId × api × bizDate）。
 *
 * <p><b>资金隔离红线</b>：billedAmount 只是开放平台<b>服务计费</b>计量（long 分），
 * 落 member 库 openapi_call_log，<b>不产生任何账务分录/支付单/余额变动</b>。
 */
@Data
public class ApiBillDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    private String api;
    private String bizDate;
    private Long callCount;
    private Long rejectCount;
    private Long unitPrice;
    private Long billedAmount;
    private Long updatedAt;
}