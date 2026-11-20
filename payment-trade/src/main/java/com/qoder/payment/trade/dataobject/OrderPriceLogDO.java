package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-6 改价留痕（单库不分片：request_id UNIQUE 全局幂等键）。 */
@Data
public class OrderPriceLogDO {

    private String id;
    private String orderId;
    private Long oldAmount;
    private Long newAmount;
    private Long delta;
    /** UNPAID / REFUND_DELTA / SUPPLEMENT / NONE */
    private String direction;
    private String reason;
    private String operator;
    private String requestId;
    private String refundId;
    private String supplementOrderId;
    private Long createdAt;
}