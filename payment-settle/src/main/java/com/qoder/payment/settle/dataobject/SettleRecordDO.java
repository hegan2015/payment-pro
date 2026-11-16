package com.qoder.payment.settle.dataobject;

import lombok.Data;

@Data
public class SettleRecordDO {

    private String id;
    private String type;
    private String bizId;
    private String paymentId;
    private String orderId;
    private String merchantId;
    private Long gross;
    private Long fee;
    private Long net;
    private String method;
    private String status;
    private Long createdAt;
}