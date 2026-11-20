package com.qoder.payment.account.dataobject;

import lombok.Data;

/** M-3 分级限额规则（I/II/III 类户）；DynamicConfig 键 account.level.<L>.* 可热覆盖。 */
@Data
public class AccountLevelRuleDO {

    private String level;
    private Long singleLimit;
    private Long dailyLimit;
    private Long balanceLimit;
    /** NONE/FOUR_ELEMENT/STRONG。 */
    private String requireKyc;
}