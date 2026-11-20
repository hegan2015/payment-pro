package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-6 代扣单：一次授权扣款，每次尝试对应一个既有支付单（TCC + 复式记账 + 幂等）。 */
@Data
public class SignDeductDO {

    private Long id;
    private String deductNo;
    private String signNo;
    private String accountId;
    private String merchantId;
    private String requestId;
    private String attemptRequestId;
    private String orderId;
    private String paymentId;
    private Long amount;
    /** INIT / PROCESSING / SUCCESS / FAILED / TERMINATED。 */
    private String status;
    private Integer attempts;
    private Integer maxRetry;
    private Long nextRetryAt;
    private String failReason;
    private String periodKey;
    private String channelCode;
    private Long createdAt;
    private Long updatedAt;
    private Long finishedAt;
}