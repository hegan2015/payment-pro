package com.qoder.payment.account.dataobject;

import lombok.Data;

/** A-2 绑卡：卡号 SHA-256 哈希 + 掩码（后四位），绝不落明文；手机号哈希+掩码。 */
@Data
public class BankCardDO {

    private String id;
    private String accountId;
    private String cardNoHash;
    private String cardNoMask;
    private String bankName;
    private String mobileHash;
    private String mobileMask;
    private String nameMask;
    /** BOUND/UNBOUND。 */
    private String status;
    private Integer isDefault;
    private String signNo;
    private Long createdAt;
    private Long unboundAt;
}