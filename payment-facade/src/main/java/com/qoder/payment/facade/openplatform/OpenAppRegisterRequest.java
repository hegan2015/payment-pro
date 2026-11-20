package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** G-4a 应用注册入参（沙箱应用自动生成独立 appId/密钥；生产应用绑定既有 appId）。 */
@Data
public class OpenAppRegisterRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 生产应用必填：既有商户 appId（member 域已存在）。沙箱应用留空 → 服务端生成 sbx* 独立 appId。 */
    private String appId;
    private String name;
    /** 缺省 ["*"]（默认充足，不误拦）。 */
    private List<String> scopes;
    /** 缺省 0=不额外限流。 */
    private Integer rateLimit;
    /** 缺省 0=不限配额。 */
    private Long dailyQuota;
    /** 缺省 0=免费（历史商户兼容）。 */
    private Long unitPrice;
    /** true=沙箱（独立密钥 + 沙箱账本，绝不触碰生产资金）。 */
    private Boolean sandbox;
    private String apiVersion;
    private String operator;
}