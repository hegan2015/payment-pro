package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-3 账户分级限额（I/II/III 类户）：限额经 DynamicConfig 可配，默认值不误拦既有流量。 */
@Data
public class AccountLevelDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    /** I/II/III。 */
    private String level;
    /** 单笔限额（分）。 */
    private Long singleLimit;
    /** 日累计限额（分，R-2 风控接线执行）。 */
    private Long dailyLimit;
    /** 余额上限（分）。 */
    private Long balanceLimit;
    /** 升级所需 KYC：NONE/FOUR_ELEMENT/STRONG。 */
    private String requireKyc;
    /** 会员当前 KYC 等级（未实名 null）。 */
    private String kycLevel;
}