package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 活动核销请求。幂等键 {@code (ACTIVITY_APPLY, activityId:paymentId)} +
 * {@code activity_use} UNIQUE(activity_id, payment_id) + UNIQUE(request_id)。
 */
@Data
public class ActivityApplyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String activityId;
    private String paymentId;
    private String orderId;
    private String memberId;
    private String accountId;
    /** 订单金额（分），用于门槛与比例计算，必填。 */
    private Long orderAmount;
    private String requestId;
    private String operator;
}