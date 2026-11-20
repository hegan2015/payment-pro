package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** S-7 大额双人复核单（amount_review）。 */
@Data
public class ReviewDO {

    private String id;
    private String bizType;
    private String bizNo;
    private Long amount;
    private String status;
    private String submitter;
    private String approver;
    private String rejectReason;
    private Long createdAt;
    private Long reviewedAt;
}