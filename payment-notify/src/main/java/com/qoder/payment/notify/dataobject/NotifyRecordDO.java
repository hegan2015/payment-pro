package com.qoder.payment.notify.dataobject;

import lombok.Data;

@Data
public class NotifyRecordDO {

    private String id;
    private String event;
    private String bizId;
    private String payload;
    private String callbackUrl;
    private String status;
    private Long attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
    /** 商户 appId（HMAC 取密钥用，非敏感标识）；为空走平台共享密钥。 */
    private String appId;
    /** 签名算法：登记时为请求值，推送后落实际值（RSA2 降级 HMAC）。 */
    private String signType;
    private String signature;
    private String nonce;
    /** N-4 additive：累计重发次数。 */
    private Long resendCount;
}