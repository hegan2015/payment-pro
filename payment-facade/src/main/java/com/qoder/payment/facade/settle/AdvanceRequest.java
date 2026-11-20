package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-6 垫资/追偿请求。(ADVANCE,requestId) / (ADVANCE_RECOVER,requestId) 幂等。 */
@Data
public class AdvanceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    /** 垫资入账账户（可空=商户 BALANCE 户）。 */
    private String accountId;
    private Long amount;
    private String requestId;
    /** 追偿指定垫资单（追偿必填）。 */
    private String advanceId;
    private String operator;
    private String reason;
}