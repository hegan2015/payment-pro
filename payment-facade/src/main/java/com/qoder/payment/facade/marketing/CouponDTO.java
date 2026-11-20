package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3a 优惠券。状态机 AVAILABLE → USED → REFUNDED；AVAILABLE → EXPIRED（过期作业）。 */
@Data
public class CouponDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    private String templateId;
    /** 面额（分）。 */
    private Long amount;
    /** 使用门槛（分）。 */
    private Long threshold;
    /** AVAILABLE / USED / EXPIRED / REFUNDED。 */
    private String status;
    private Long expireAt;
    /** 发放幂等键（调用方 bizNo 派生，UNIQUE）。 */
    private String requestId;
    /** 来源业务号（发放批次/领取单）。 */
    private String bizNo;
    /** 核销单 id（USED/REFUNDED 后有值）。 */
    private String useId;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}