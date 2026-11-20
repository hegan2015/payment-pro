package com.qoder.payment.cashier.model;

import lombok.Data;

/** C-3 验证会话持久化行（一次性 verifyToken，绑定 order+amount+payer）。 */
@Data
public class VerifySessionDO {
    private String id;
    private String memberId;
    private String accountId;
    private String orderId;
    private Long amount;
    private String verifyType;
    private String status;
    private Long expireAt;
    private String requestId;
    private Long createdAt;
    private Long usedAt;
}