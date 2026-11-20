package com.qoder.payment.cashier.model;

import lombok.Data;

/** C-2 付款码持久化行（内部 DO，含核销幂等键；对外经 PayCodeDTO 脱敏转换）。 */
@Data
public class PayCodeDO {
    private String code;
    private String type;
    private String accountId;
    private String orderId;
    private Long amount;
    private String status;
    private Long expireAt;
    private String requestId;
    private String consumeRequestId;
    private String qrContent;
    private String merchantName;
    private String subject;
    private Long createdAt;
    private Long consumedAt;
}