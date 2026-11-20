package com.qoder.payment.account.dataobject;

import lombok.Data;

/** A-3 冻结/解冻凭证留痕（只追加）：状态级与金额级动作全记录。 */
@Data
public class FreezeLogDO {

    private String id;
    private String accountId;
    /** STATUS_FREEZE/STATUS_UNFREEZE/AMOUNT_FREEZE/AMOUNT_UNFREEZE。 */
    private String action;
    private String freezeType;
    private String reasonType;
    private String reason;
    private Long amount;
    private String operator;
    private String authorizedBy;
    private String bizNo;
    private Long createdAt;
}