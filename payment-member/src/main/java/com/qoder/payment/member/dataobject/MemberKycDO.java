package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-2 KYC 记录：敏感要素哈希存储（SHA-256），出参仅掩码；绝不落明文。 */
@Data
public class MemberKycDO {

    private String id;
    private String memberId;
    private String nameHash;
    private String nameMask;
    private String idCardNoHash;
    private String idCardMask;
    private String bankCardNoHash;
    private String bankCardMask;
    private String mobileHash;
    private String mobileMask;
    /** PASS/FAIL。 */
    private String verifyStatus;
    /** PASS → II。 */
    private String level;
    private String reason;
    private Long verifiedAt;
    private Long createdAt;
    private Long updatedAt;
}