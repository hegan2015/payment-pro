package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** P-4 退款单独立视图（异步化：PROCESSING→SUCCESS/FAILED 独立终态，供轮询/补单）。 */
@Data
public class RefundDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String paymentId;
    private Long amount;
    private Long feeRefund;
    private String status;
    private String requestId;
    private String bizNo;
    private String channelOrderNo;
    private String failReason;
    private Integer reconcileCount;
    private Long createdAt;
}