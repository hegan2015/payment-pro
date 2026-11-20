package com.qoder.payment.member.dataobject;

import lombok.Data;

/** 商户应用公钥证书（M-1/S-1）。 */
@Data
public class MemberCertDO {

    private String id;
    private String memberId;
    private String certSn;
    private String certPem;
    private String publicKey;
    private Long notBefore;
    private Long notAfter;
    /** ACTIVE / REVOKED */
    private String status;
    private Long createdAt;
}