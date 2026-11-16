package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 交易 TCC 上下文：支付预留 / 退款标记共用。 */
@Data
public class TradeTccContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String paymentId;
    private Long amount;
    private String bizNo;
}