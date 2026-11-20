package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-5 分阶段计划行（单库不分片：UNIQUE(order_id,stage_seq)）。 */
@Data
public class OrderStagePlanDO {

    private String orderId;
    private Integer stageSeq;
    private Long amount;
}