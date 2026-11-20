package com.qoder.payment.account.dataobject;

import lombok.Data;

/** A-3 金额冻结单（按原因细分）：ACTIVE 冻结中 / RELEASED 已解冻；bizNo 幂等。 */
@Data
public class AccountFreezeDO {

    private String id;
    private String accountId;
    private Long amount;
    /** RISK/JUDICIAL/PREAUTH/DISPUTE。 */
    private String reasonType;
    private String reason;
    private String status;
    private String operator;
    private String authorizedBy;
    private String bizNo;
    private Long createdAt;
    private Long releasedAt;
    private String releaseBizNo;
}