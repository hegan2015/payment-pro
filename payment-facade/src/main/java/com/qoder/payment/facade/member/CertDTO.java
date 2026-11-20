package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 商户应用公钥证书（M-1/S-1 RSA2）。 */
@Data
public class CertDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    /** 证书序列号 hex。 */
    private String certSn;
    /** Base64(X509 SubjectPublicKeyInfo)，供 RSA2 验签。 */
    private String publicKey;
    private Long notBefore;
    private Long notAfter;
    /** ACTIVE / REVOKED */
    private String status;
    private Long createdAt;
    /** 距到期剩余天数（向下取整，可为负）。 */
    private Long daysLeft;
}