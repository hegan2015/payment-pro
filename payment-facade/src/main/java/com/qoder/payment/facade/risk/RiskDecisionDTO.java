package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-1/R-4 处置留痕（risk_decision_log 投影，可解释：命中规则 + 因子明细）。 */
@Data
public class RiskDecisionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String payerId;
    private String merchantId;
    private Long amount;
    private String scene;
    private String method;
    private String channelCode;
    private String cardMask;
    private String deviceId;
    private String ip;
    private Integer score;
    private String action;
    private Boolean pass;
    private String reason;
    private String code;
    /** 命中规则（JSON 数组字符串）。 */
    private String hitRules;
    /** 因子明细（JSON 数组字符串）。 */
    private String factors;
    private Boolean shadow;
    private Boolean degraded;
    private Integer ruleVersion;
    private String listHit;
    private String quotaHit;
    private String traceId;
    private String bizNo;
    private Long disputeId;
    /** 人审状态：null/PENDING/APPROVED/REJECTED。 */
    private String reviewStatus;
    private Long createdAt;
}