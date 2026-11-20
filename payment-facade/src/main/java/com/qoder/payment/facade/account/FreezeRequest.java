package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-3 金额冻结请求（按原因细分）。bizNo 幂等键；
 * 经账务 TCC 参与者 Try 冻结（available=balance-frozen 口径不变），不产生分录。
 */
@Data
public class FreezeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    /** 冻结金额（分），>0 且 ≤ 可用余额。 */
    private Long amount;
    /** RISK/JUDICIAL/PREAUTH/DISPUTE。 */
    private String reasonType;
    private String reason;
    private String operator;
    /** 幂等键（必填）。 */
    private String bizNo;
}