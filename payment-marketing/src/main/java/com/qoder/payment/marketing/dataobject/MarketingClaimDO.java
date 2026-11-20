package com.qoder.payment.marketing.dataobject;

import lombok.Data;

/** X-3a 领取留痕行（每人限领计数 + 领取幂等）。 */
@Data
public class MarketingClaimDO {

    private String id;
    private String templateId;
    private String memberId;
    private String kind;
    private String voucherId;
    private String bizNo;
    private Long createdAt;
}