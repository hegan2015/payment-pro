package com.qoder.payment.facade.review;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** S-7 大额双人复核单。status: PENDING/APPROVED/REJECTED；同人提交+审批被拒（双人原则）。 */
@Data
public class ReviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    /** REFUND / REVERSE / WITHDRAW（业务类型）。 */
    private String bizType;
    /** 业务幂等键：退款=requestId，冲正=原凭证 bizNo。UNIQUE(bizType,bizNo)。 */
    private String bizNo;
    private Long amount;
    private String status;
    private String submitter;
    private String approver;
    private String rejectReason;
    private Long createdAt;
    private Long reviewedAt;
}