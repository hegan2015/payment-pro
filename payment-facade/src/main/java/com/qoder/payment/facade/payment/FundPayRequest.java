package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 资金操作支付请求（充值/提现）：区别于商户交易，无手续费，走系统资金商户 SYS_FUND。 */
@Data
public class FundPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** TOPUP（充值，外部现金入户）/ WITHDRAW（提现，账户出金）。 */
    private String bizType;
    private String accountId;
    private Long amount;
    /** 幂等键（调用方 bizNo）。 */
    private String requestId;
    private String callbackUrl;
}