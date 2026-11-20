package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class NotifyRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String event;
    private String bizId;
    private Map<String, Object> payload;
    private String callbackUrl;
    private String status;
    private Long attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
    /** 实际签名算法（RSA2 降级时为 HMAC_SHA256），便于商户补查验签。 */
    private String signType;
    /** 最近一次推送的签名值（HMAC hex / RSA2 base64）。 */
    private String signature;
    /** 最近一次推送的随机 nonce（商户侧 nonce 去重防重放）。 */
    private String nonce;
    /** N-3/N-4 additive：商户 appId（非敏感公开标识；密钥永不出现在任何 DTO）。 */
    private String appId;
    /** N-4 additive：累计重发次数（resend 成功重置一次 +1）。 */
    private Long resendCount;
}