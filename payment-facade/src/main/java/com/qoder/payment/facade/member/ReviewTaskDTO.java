package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-2 审核任务（留痕）：PENDING/APPROVED/REJECTED/SUPPLEMENT，审核意见只追加。 */
@Data
public class ReviewTaskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String applicationId;
    private String reviewer;
    private String status;
    private String opinion;
    private Long createdAt;
    private Long reviewedAt;
}