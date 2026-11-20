package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class NotifyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String event;
    private String bizId;
    private Map<String, Object> payload;
    private String callbackUrl;
    /** 商户 appId：HMAC 签名取该商户 appSecret（经 memberFacade.getByAppId 内部接口）；为空则用平台共享密钥。 */
    private String appId;
    /** 签名算法：HMAC_SHA256（默认）/ RSA2（平台私钥，local 无证书时降级 HMAC 并标记 signType）。 */
    private String signType = "HMAC_SHA256";
}