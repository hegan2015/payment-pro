package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** T-6 已支付改价结果：方向 + 差额 + 退款单/补差单 linkage（守恒证据）。 */
@Data
public class AdjustPriceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** DOWN / UP / NONE */
    private String direction;
    /** 改价前净价 = 订单额 − 已退额。 */
    private Long oldEffective;
    private Long newAmount;
    /** |newAmount − oldEffective|。 */
    private Long delta;
    private String refundId;
    private String refundStatus;
    private String supplementOrderId;
    private String supplementPaymentId;
    private Integer priceChangeCount;
    private Boolean repeated;
}