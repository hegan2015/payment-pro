package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-4 商户结算卡（脱敏出参；ST-4 提现联动读取）。 */
@Data
public class SettleCardDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    private String cardNoMask;
    private String bankName;
    /** DEFAULT/BOUND。 */
    private String status;
    private Long createdAt;
}