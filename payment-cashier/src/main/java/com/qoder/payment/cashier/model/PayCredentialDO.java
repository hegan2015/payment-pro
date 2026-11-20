package com.qoder.payment.cashier.model;

import lombok.Data;

/** C-3 支付口令持久化行（哈希加盐，明文不落库）。 */
@Data
public class PayCredentialDO {
    private String memberId;
    private String passwordHash;
    private String salt;
    private Integer failCount;
    private Long lockedUntil;
    private Long createdAt;
    private Long updatedAt;
}