package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** P-2 组合支付腿明细（additive，只读视图；卡号脱敏）。 */
@Data
public class PaymentLegDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String paymentId;
    private Integer legSeq;
    private String method;
    private String payerAccountId;
    private String cardNo;
    private Long amount;
    private Long fee;
    private String status;
    private String channelBizNo;
    private String channelCode;
    private Long refundedAmount;
    private Long refundedFee;
}