package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** A-3 冻结单（凭证留痕）：ACTIVE 冻结中 / RELEASED 已解冻。 */
@Data
public class AccountFreezeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String accountId;
    private Long amount;
    private String reasonType;
    private String reason;
    /** ACTIVE/RELEASED。 */
    private String status;
    private String operator;
    private String authorizedBy;
    private String bizNo;
    private Long createdAt;
    private Long releasedAt;
    private String releaseBizNo;
}