package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** P-3 分账单（share_order，单库不分片；编排属 payment，动账登记属 settle）。 */
@Data
public class ShareOrderDO {

    private String id;
    private String paymentId;
    private String merchantId;
    private String merchantAccountId;
    private Long totalAmount;
    /** REALTIME / DELAYED。 */
    private String shareType;
    /** PENDING/SHARED/BACKING/BACKED。 */
    private String status;
    private String requestId;
    private String bizNo;
    private Long executeAt;
    private Long createdAt;
    private Long sharedAt;
    private Long backedAt;
    private String backRequestId;
    private String failReason;
}