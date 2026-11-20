package com.qoder.payment.payment.dataobject;

import lombok.Data;

/**
 * 风控 CHALLENGE 续付留痕 + 单_use 续付授权（payment_challenge，单库不分片）。
 * CHALLENGED（原受理被拒留痕）→ CONTINUING（续付授权窗口，绑定新 requestId）→ CONSUMED/回退 CHALLENGED。
 */
@Data
public class ChallengeDO {

    private String requestId;
    private String orderId;
    private String payerKey;
    private Long amount;
    private String method;
    /** CHALLENGED/CONTINUING/CONSUMED。 */
    private String status;
    private String continueRequestId;
    private Long createdAt;
    private Long expireAt;
    private Long consumedAt;
}