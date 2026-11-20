package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-5 阶段支付留痕（单库不分片：UNIQUE(order_id,stage_seq) + UNIQUE(payment_id) + UNIQUE(request_id)）。 */
@Data
public class StagePayLogDO {

    private String id;
    private String orderId;
    private Integer stageSeq;
    private Long amount;
    private String paymentId;
    private String requestId;
    /** PROCESSING / SUCCESS / FAILED / CANCELLED */
    private String status;
    private Long createdAt;
    private Long updatedAt;
}