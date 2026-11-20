package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * M-4 进件单出参（脱敏）。状态机：DRAFT→SUBMITTED→APPROVED/REJECTED/SUPPLEMENT（补件），
 * REJECTED/SUPPLEMENT 可重提→SUBMITTED。appSecret 仅审核通过当次一次性返回（E-1 口径）。
 */
@Data
public class MerchantApplicationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantName;
    private String licenseNoMask;
    private String legalPersonMask;
    private String contactMask;
    private String qualification;
    /** DRAFT/SUBMITTED/APPROVED/REJECTED/SUPPLEMENT。 */
    private String status;
    private String rejectReason;
    /** 审核通过后创建的 MERCHANT 会员 id。 */
    private String memberId;
    private String appId;
    /** 仅 approve 当次返回一次；其余查询一律 null（E-1）。 */
    private String appSecret;
    private String submitter;
    private Long createdAt;
    private Long reviewedAt;
}