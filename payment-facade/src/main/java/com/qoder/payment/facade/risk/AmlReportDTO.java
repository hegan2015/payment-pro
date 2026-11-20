package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * R-6 可疑交易报告（STR）。
 *
 * <p>幂等键 {@code (payerId, ruleId, bizDate)}（DB 唯一索引兜底，重复监测返回既有报告不重复建单）。
 * <p>状态机 {@code PENDING（待审）→ SUBMITTED（已上报）→ CLOSED（归档）}，
 * 亦允许 {@code PENDING → CLOSED}（排除误报直接归档）；每次流转写 {@code aml_audit} 审计行。
 * <p>{@link #disposition} 由既有 {@code DispositionService} 分级得出；达到人审档经
 * {@code DisputeFacade} 建单（source=PAYMENT、type=AML_STR、strategy=MANUAL，<b>不自动动账</b>）。
 */
@Data
public class AmlReportDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 报告编号 STR{id}。 */
    private String reportNo;
    private String payerId;
    private String merchantId;
    private String ruleId;
    private String ruleType;
    private Long txnAmount;
    private Integer txnCount;
    /** 业务日 yyyyMMdd（Asia/Shanghai）。 */
    private String bizDate;
    /** PENDING / SUBMITTED / CLOSED。 */
    private String status;
    /** 命中贡献分。 */
    private Integer score;
    /** 既有分级处置结论（PASS/CHALLENGE/LIMIT/REJECT/MANUAL_REVIEW）。 */
    private String disposition;
    /** 是否硬规则命中（硬规则才可能阻断；默认全 false）。 */
    private Boolean hardRule;
    /** 明细 JSON（聚合观测值 + 规则口径 + 交易引用）。 */
    private String detail;
    private Long disputeId;
    private String operator;
    private String submitNote;
    private Long submittedAt;
    private Long closedAt;
    private Long retentionUntil;
    private Long createdAt;
    private Long updatedAt;
    /** 关联交易引用（aml_txn_ref）。 */
    private List<String> paymentIds = new ArrayList<>();
}