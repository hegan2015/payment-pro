package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3a 红包（无门槛现金券语义，面额即抵扣额）。状态机与优惠券一致。 */
@Data
public class RedPacketDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    private String templateId;
    private Long amount;
    /** AVAILABLE / USED / EXPIRED / REFUNDED。 */
    private String status;
    private Long expireAt;
    private String requestId;
    private String bizNo;
    private String useId;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}