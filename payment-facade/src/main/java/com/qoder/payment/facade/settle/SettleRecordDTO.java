package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SettleRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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