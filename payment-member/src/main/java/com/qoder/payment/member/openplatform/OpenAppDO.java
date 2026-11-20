package com.qoder.payment.member.openplatform;

import lombok.Data;

/** G-4a 开放平台应用（openapi_app）。scopes 逗号分隔；沙箱密钥只存 AES-GCM 密文。 */
@Data
public class OpenAppDO {

    private String appId;
    private String merchantId;
    private String name;
    private String scopes;
    private Integer rateLimit;
    private Long dailyQuota;
    private Long unitPrice;
    private Integer sandbox;
    private String status;
    private String apiVersion;
    private String secretCipher;
    private String secretFingerprint;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
    private Long revokedAt;
}