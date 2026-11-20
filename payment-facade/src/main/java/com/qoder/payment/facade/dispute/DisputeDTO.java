package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 差错单 DTO（X-1 差错处理平台）。 */
@Data
public class DisputeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String disputeNo;
    /** AMOUNT_MISMATCH/ONE_SIDED/STATUS_MISMATCH/FEE_MISMATCH/CHANNEL_MISSING/NOTIFY_DEAD/SOURCE_UNAVAILABLE。 */
    private String type;
    /** RECON/NOTIFY/PAYMENT/RESERVE。 */
    private String source;
    private String bizNo;
    private String paymentId;
    private Long amount;
    /** OPEN/ASSIGNED/FIXING/RESOLVED/CLOSED/IGNORED。 */
    private String status;
    /** REVERSE_AND_REPOST/SUSPENSE/NOTIFY_REPLAY/MANUAL。 */
    private String strategy;
    private String assignee;
    private String reason;
    private String fixBizNo;
    private String checkName;
    private String bizDate;
    private Long createdAt;
    private Long updatedAt;
    private Long resolvedAt;
    /** 仅 get(id) 详情返回：全程审计留痕。 */
    private java.util.List<DisputeFixLogDTO> logs;
}