package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** 差错处理审计日志（pp_recon.dispute_fix_log）。 */
@Data
public class DisputeFixLogDO {

    private Long id;
    private Long disputeId;
    private String action;
    private String operator;
    private String beforeStatus;
    private String afterStatus;
    private String detail;
    private Long createdAt;
}