package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 差错处理审计日志（dispute_fix_log）。 */
@Data
public class DisputeFixLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long disputeId;
    /** CREATE/ASSIGN/APPROVE/FIX_START/FIX_SUCCESS/FIX_FAIL/REJECT/RESOLVE/AUTO_RESOLVE/CLOSE/REPLAY_TRIGGERED/REPLAY_DELIVERED。 */
    private String action;
    private String operator;
    private String beforeStatus;
    private String afterStatus;
    private String detail;
    private Long createdAt;
}