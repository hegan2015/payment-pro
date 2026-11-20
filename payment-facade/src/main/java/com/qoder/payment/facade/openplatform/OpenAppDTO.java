package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * G-4a 开放平台应用（脱敏出参）。
 *
 * <p><b>E-1 口径延续</b>：{@code appSecret} 仅 registerApp（沙箱应用创建）一次性返回；
 * list/get 一律 null，只给 {@code secretFingerprint}。生产应用的密钥仍由 member 域密钥版本表管理，
 * 开放平台不复制、不返回。
 */
@Data
public class OpenAppDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    /** 生产应用：绑定的既有商户会员 id；沙箱应用：沙箱虚拟商户 id（SBXM*，与生产会员表无关）。 */
    private String merchantId;
    private String name;
    private List<String> scopes;
    /** 每 appId QPS 上限（0=不额外限流，仅 I-7 Sentinel 保守阈值）。 */
    private Integer rateLimit;
    /** 日调用配额（0=不限，默认充足）。 */
    private Long dailyQuota;
    /** 计费单价（分/次，long；0=免费）。 */
    private Long unitPrice;
    private Boolean sandbox;
    /** ACTIVE / REVOKED。 */
    private String status;
    /** 默认 API 版本（v1/v2），仅作 SDK 协商默认值，不影响 v1 端点行为。 */
    private String apiVersion;
    private String secretFingerprint;
    /** 仅沙箱应用创建时一次性返回；其余一律 null。 */
    private String appSecret;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
    private Long revokedAt;
}