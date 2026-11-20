package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-5 合并单子订单关联（单库不分片：UNIQUE(merge_id,order_id)）。 */
@Data
public class MergeRelDO {

    private String mergeId;
    private String orderId;
    private Long amount;
    private Long fee;
}