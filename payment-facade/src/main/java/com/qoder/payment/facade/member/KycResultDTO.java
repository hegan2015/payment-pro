package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-2 KYC 结果（脱敏出参：仅掩码，不含明文要素/哈希）。 */
@Data
public class KycResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private Boolean pass;
    /** PASS/FAIL。 */
    private String verifyStatus;
    /** KYC 等级：PASS → II（四要素）；未通过 null。 */
    private String level;
    /** 失败原因（不含敏感要素明文）。 */
    private String reason;
    private String nameMask;
    private String idCardMask;
    private String bankCardMask;
    private String mobileMask;
    private Long verifiedAt;
    private Long createdAt;
}