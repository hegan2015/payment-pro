package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-4 进件单：营业执照号哈希+掩码，法人/联系方式掩码；资质 JSON 留痕。 */
@Data
public class MerchantApplicationDO {

    private String id;
    private String merchantName;
    private String licenseNoHash;
    private String licenseNoMask;
    private String legalPersonMask;
    private String contactMask;
    private String qualification;
    /** DRAFT/SUBMITTED/APPROVED/REJECTED/SUPPLEMENT。 */
    private String status;
    private String rejectReason;
    private String memberId;
    private String submitter;
    private Long createdAt;
    private Long reviewedAt;
    private Long updatedAt;
}