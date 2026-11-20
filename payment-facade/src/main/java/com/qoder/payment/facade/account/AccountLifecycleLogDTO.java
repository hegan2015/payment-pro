package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** A-5 账户生命周期留痕（只追加不可改；销户为逻辑状态，账户行永久保留）。 */
@Data
public class AccountLifecycleLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String accountId;
    /** OPEN / CLOSE_REQUEST / CLOSE / CLOSE_ABORT / UPGRADE / DOWNGRADE。 */
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