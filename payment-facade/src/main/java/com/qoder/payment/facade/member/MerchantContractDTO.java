package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-4 商户签约（产品/费率，ST-2 结算联动读取）。 */
@Data
public class MerchantContractDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String applicationId;
    private String merchantId;
    /** 签约产品（逗号分隔，如 BALANCE,CARD,QUICK_PAY）。 */
    private String products;
    /** 签约费率（基点；缺省走 payment.fee-bps 全局口径，不改既有手续费）。 */
    private Integer feeBps;
    /** SIGNED。 */
    private String status;
    private String operator;
    private Long signedAt;
}