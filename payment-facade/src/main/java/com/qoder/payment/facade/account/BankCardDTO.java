package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** A-2 绑卡出参：只含脱敏卡号（后四位）与手机号掩码，绝不含明文卡号/哈希。 */
@Data
public class BankCardDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String accountId;
    /** **** **** **** 后四位。 */
    private String cardNoMask;
    private String bankName;
    private String mobileMask;
    /** BOUND/UNBOUND。 */
    private String status;
    private Boolean isDefault;
    /** 快捷支付签约号（沙箱，CH-6 真实签约接线）。 */
    private String signNo;
    private Long createdAt;
    private Long unboundAt;
}