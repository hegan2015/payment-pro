package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-3 解冻请求：必须对应原冻结原因（reasonType 与冻结单一致），
 * JUDICIAL 解冻需 authorizedBy 双人复核（≠operator）。bizNo 幂等。
 * freezeId 与 (accountId+reasonType) 二选一定位冻结单。
 */
@Data
public class UnfreezeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String freezeId;
    private String accountId;
    /** 必填：须与冻结单原因一致。 */
    private String reasonType;
    private String operator;
    /** JUDICIAL 必填：复核人（≠operator）。 */
    private String authorizedBy;
    /** 幂等键（必填）。 */
    private String bizNo;
}