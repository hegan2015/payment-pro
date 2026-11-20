package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3a/X-3b 营销审计留痕（只追加，状态迁移/资金动作/预算变动全留痕）。 */
@Data
public class MarketingAuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    /** COUPON / RED_PACKET / POINTS / ACTIVITY / TEMPLATE / ALLOC。 */
    private String entityType;
    private String entityId;
    /** ISSUE / CLAIM / USE / REFUND / EXPIRE / EARN / FREEZE / UNFREEZE / SETTLE / ALLOCATE / BUDGET。 */
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long amount;
    private String bizNo;
    private String operator;
    private String detail;
    private Long createdAt;
}