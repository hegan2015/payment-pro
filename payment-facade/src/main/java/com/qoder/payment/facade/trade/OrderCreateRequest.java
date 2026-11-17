package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private String subject;
    private Long amount;
    /** 商户外部交易号（可选）：同商户下幂等，重复下单返回已有订单。 */
    private String outTradeNo;
    /** 单据类型：TRADE（商户交易，默认）/ TOPUP（充值）/ WITHDRAW（提现）。 */
    private String bizType;
}