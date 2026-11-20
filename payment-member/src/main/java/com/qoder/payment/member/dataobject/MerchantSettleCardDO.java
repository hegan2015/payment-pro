package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-4 商户结算卡：卡号哈希+掩码（后四位），绝不落明文。 */
@Data
public class MerchantSettleCardDO {

    private String id;
    private String merchantId;
    private String cardNoHash;
    private String cardNoMask;
    private String bankName;
    /** DEFAULT/BOUND。 */
    private String status;
    private Long createdAt;
}