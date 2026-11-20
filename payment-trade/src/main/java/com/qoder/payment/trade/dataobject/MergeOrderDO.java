package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-5 合并单（单库不分片：request_id 全局幂等键）。 */
@Data
public class MergeOrderDO {

    private String id;
    private String merchantId;
    private Long totalAmount;
    private String status;
    private String requestId;
    private String carrierOrderId;
    private String paymentId;
    private Integer orderCount;
    private Integer paidCount;
    private Long createdAt;
    private Long paidAt;
}