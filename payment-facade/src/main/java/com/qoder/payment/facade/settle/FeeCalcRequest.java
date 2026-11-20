package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-2 计费预览请求（只算不落库，不影响任何在途单）。 */
@Data
public class FeeCalcRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private String productType;
    private String method;
    private Long amount;
}