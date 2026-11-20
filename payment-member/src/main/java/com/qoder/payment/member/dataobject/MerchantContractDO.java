package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-4 商户签约。 */
@Data
public class MerchantContractDO {

    private String id;
    private String applicationId;
    private String merchantId;
    private String products;
    private Integer feeBps;
    private String status;
    private String operator;
    private Long signedAt;
}