package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-3 账户状态冻结/解冻请求（状态级闸门）。
 * FROZEN 时 freezeType 缺省 FULL（兼容既有语义）；JUDICIAL 解冻需 authorizedBy ≠ operator。
 */
@Data
public class StatusChangeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    /** ACTIVE/FROZEN。 */
    private String status;
    /** NONE/FULL/JUDICIAL（FROZEN 缺省 FULL）。 */
    private String freezeType;
    private String reason;
    private String operator;
    /** JUDICIAL 解冻必填：复核人（≠operator）。 */
    private String authorizedBy;
}