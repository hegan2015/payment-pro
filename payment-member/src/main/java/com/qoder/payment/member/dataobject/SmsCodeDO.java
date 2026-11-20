package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-5 短信验证码（沙箱）：验证码哈希存储，5 分钟有效，一次性使用。 */
@Data
public class SmsCodeDO {

    private String id;
    private String mobileHash;
    private String codeHash;
    private Long expiresAt;
    private Integer used;
    private Long createdAt;
}