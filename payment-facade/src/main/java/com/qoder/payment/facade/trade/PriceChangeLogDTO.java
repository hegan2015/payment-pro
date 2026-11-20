package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** T-6 改价留痕行：UNPAID=未支付直接改额；REFUND_DELTA=已支付差额退款；SUPPLEMENT=已支付差额补付；NONE=等额。 */
@Data
public class PriceChangeLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String orderId;
    private Long oldAmount;
    private Long newAmount;
    private Long delta;
    private String direction;
    private String reason;
    private String operator;
    private String requestId;
    private String refundId;
    private String supplementOrderId;
    private Long createdAt;
}