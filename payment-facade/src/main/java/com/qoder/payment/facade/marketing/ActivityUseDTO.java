package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3b 活动核销单。状态机 USED → REFUNDED（退回，预算回滚 + 资金反向）。 */
@Data
public class ActivityUseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String activityId;
    private String paymentId;
    private String orderId;
    private String memberId;
    private String accountId;
    /** 立减额（分）。 */
    private Long amount;
    private Long orderAmount;
    private String bizNo;
    /** USED / REFUNDED。 */
    private String status;
    private String requestId;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}