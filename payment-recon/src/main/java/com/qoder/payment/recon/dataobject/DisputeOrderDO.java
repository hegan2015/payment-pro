package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** 差错单（X-1，pp_recon.dispute_order）。 */
@Data
public class DisputeOrderDO {

    private Long id;
    private String disputeNo;
    private String type;
    private String source;
    private String bizNo;
    private String paymentId;
    private Long amount;
    private String status;
    private String strategy;
    private String assignee;
    private String reason;
    private String fixBizNo;
    private String checkName;
    private String bizDate;
    private Long createdAt;
    private Long updatedAt;
    private Long resolvedAt;
}