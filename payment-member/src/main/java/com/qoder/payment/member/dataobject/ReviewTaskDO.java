package com.qoder.payment.member.dataobject;

import lombok.Data;

/** X-2 审核任务（只追加留痕，历史任务不改写）。 */
@Data
public class ReviewTaskDO {

    private String id;
    private String applicationId;
    private String reviewer;
    /** PENDING/APPROVED/REJECTED/SUPPLEMENT。 */
    private String status;
    private String opinion;
    private Long createdAt;
    private Long reviewedAt;
}