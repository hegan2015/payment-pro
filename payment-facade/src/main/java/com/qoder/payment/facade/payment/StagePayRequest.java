package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * T-5 分阶段付款请求：按期顺序支付（stageSeq 必须 = paidStage+1），
 * amount 缺省取阶段计划额，显式传入必须与计划额一致（总额守恒）。requestId 幂等。
 */
@Data
public class StagePayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private Integer stageSeq;
    private Long amount;
    private String accountId;
    private String requestId;
    private String callbackUrl;
}