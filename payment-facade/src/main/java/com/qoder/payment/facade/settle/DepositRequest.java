package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 保证金请求（缴存/释放/扣划共用；amount 单位分，long）。
 *
 * <p>缴存：D 出资账户 / C SYS_DEPOSIT（2006 保证金负债）；(DEPOSIT_FREEZE,requestId) 幂等。
 * <p>释放：D SYS_DEPOSIT / C 原出资账户；(DEPOSIT_RELEASE,requestId) 幂等，<b>敏感需 step-up</b>。
 * <p>扣划：D SYS_DEPOSIT / C targetAccount（缺省 SYS_FEE 平台收入；给 advanceId 则 C SYS_ADVANCE
 * 并同步驱动垫资回收）；(DEPOSIT_DEDUCT,requestId) 幂等，<b>敏感需 step-up</b>。
 */
@Data
public class DepositRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    /** 缴存出资账户（可空=商户 DEPOSIT 专户，A-1 幂等懒开户）。 */
    private String accountId;
    private Long amount;
    /** 幂等键（必填，≤60 字符）。 */
    private String requestId;
    /** 释放/扣划指定原缴存单（可空=按 FIFO 取最早可用缴存单）。 */
    private String depositId;
    /** 扣划去向（可空=SYS_FEE；给了 advanceId 则恒 SYS_ADVANCE）。 */
    private String targetAccount;
    /** 扣划用于偿还的垫资单（可空）。 */
    private String advanceId;
    private String operator;
    private String reason;
}