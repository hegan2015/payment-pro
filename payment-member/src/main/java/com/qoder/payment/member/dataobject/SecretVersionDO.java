package com.qoder.payment.member.dataobject;

import lombok.Data;

/** 商户密钥版本（M-1）：secretCipher 为 AES-GCM 密文，主密钥经 SecretProvider。 */
@Data
public class SecretVersionDO {

    private String memberId;
    private Integer version;
    private String secretCipher;
    /** ACTIVE / RETIRED */
    private String status;
    private Long createdAt;
    private Long retiredAt;
    private Long graceUntil;
}