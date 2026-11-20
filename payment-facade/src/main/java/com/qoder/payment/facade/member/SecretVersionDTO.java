package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 商户密钥版本（M-1）：只暴露版本/状态/指纹，绝不返回明文密钥。 */
@Data
public class SecretVersionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private Integer version;
    /** ACTIVE / RETIRED */
    private String status;
    /** SHA-256(密钥) 前 16 位 hex，供商户比对持有的是哪一版。 */
    private String fingerprint;
    private Long createdAt;
    private Long retiredAt;
    /** 宽限截止（毫秒）；RETIRED 且 now<graceUntil 仍可用于验签。 */
    private Long graceUntil;
}