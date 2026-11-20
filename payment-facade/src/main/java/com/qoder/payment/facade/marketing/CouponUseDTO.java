package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a 核销单（券/红包共用，{@code kind=COUPON|RED_PACKET}）。
 * 状态机 USED → REFUNDED（退回，资金反向 + 券回到 AVAILABLE）。
 */
@Data
public class CouponUseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    /** COUPON / RED_PACKET。 */
    private String kind;
    /** 券 id 或红包 id。 */
    private String voucherId;
    private String paymentId;
    private String orderId;
    private String memberId;
    private String accountId;
    /** 核销额（分）。 */
    private Long amount;
    /** 账务凭证号（确定性派生，MKT: 前缀；退回凭证 = 原号前缀 R）。 */
    private String bizNo;
    /** USED / REFUNDED。 */
    private String status;
    private String requestId;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}