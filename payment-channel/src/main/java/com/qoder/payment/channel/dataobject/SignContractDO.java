package com.qoder.payment.channel.dataobject;

import lombok.Data;

/**
 * CH-6 签约协议（channel 库，与 channel_trade 同库同事务边界）。
 * 卡号明文绝不落库：只存 hash + mask + AES-GCM 密文（主密钥经 SecretProvider）。
 */
@Data
public class SignContractDO {

    private Long id;
    private String signNo;
    private String accountId;
    private String merchantId;
    private String channelCode;
    private String channelSignNo;
    private String cardNoHash;
    private String cardNoMask;
    private String cardNoCipher;
    /** SIGNED / CANCELLED / EXPIRED。 */
    private String status;
    private Long singleLimit;
    private Long periodLimit;
    private String periodType;
    private Long effectiveAt;
    private Long expiredAt;
    private Integer deductDay;
    private Integer maxRetry;
    private Long retryIntervalMs;
    private String callbackUrl;
    private String appId;
    private String requestId;
    private String authorizedScope;
    /**
     * 有效协议唯一键（SIGNED 时 = accountId:merchantId:channelCode，UNIQUE 可空；
     * 解约/到期置 NULL）⇒ 同三元组最多一份有效协议由数据库结构性保证（并发签约不重复）。
     */
    private String activeKey;
    private Long signedAt;
    private Long cancelledAt;
    private String cancelReason;
    private Long createdAt;
    private Long updatedAt;
}