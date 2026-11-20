package com.qoder.payment.account.dataobject;

import lombok.Data;

/** A-5 账户生命周期留痕（只追加不可改）。 */
@Data
public class AccountLifecycleLogDO {

    private String id;
    private String accountId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String fromLevel;
    private String toLevel;
    private String operator;
    private String reason;
    private String bizNo;
    private Long createdAt;
}