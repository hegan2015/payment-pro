package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * T-6 已支付改价（差额补退）：newAmount 与当前净价（订单额−已退）比较，
 * 下调走退款单差额退（异步收敛），上调建补差订单并支付（accountId 必填）。
 * 不改 trade_order.amount（已支付口径），留痕 order_price_log；requestId 幂等。
 */
@Data
public class AdjustPriceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long newAmount;
    private String reason;
    private String requestId;
    /** 上调补差付款账户（BALANCE）；下调退款原路退回无需本字段。 */
    private String accountId;
}