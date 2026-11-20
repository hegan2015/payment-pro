package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 风控校验结果。
 *
 * <p><b>兼容红线（§0.7）</b>：{@code pass}/{@code reason} 语义不变——payment 域仅依据
 * {@code pass=false} 置 FAILED 并回传 reason，正常流量 pass=true/reason=null。
 * R-4 分级处置对 payment 域的收敛：非 PASS 一律 pass=false（C-3 增强认证续付未接入前
 * 保守拒绝受理，绝不落 PROCESSING 悬挂单），action 字段供控制台/后续泳道细分。
 */
@Data
public class RiskResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean pass;
    private String reason;

    // ---------- R-1/R-4 additive ----------
    /** 风险总分（0~无上限，各因子加权求和）。 */
    private Integer score;
    /** 分级处置：PASS/CHALLENGE/LIMIT/REJECT/MANUAL_REVIEW。 */
    private String action;
    /** 命中规则 id/名称列表（可解释）。 */
    private List<String> hitRules = new ArrayList<>();
    /** 命中因子明细（可解释：编码/权重/取值/得分/说明）。 */
    private List<RiskFactorDTO> factors = new ArrayList<>();
    /** 处置留痕 id（risk_decision_log）。 */
    private Long decisionId;
    /** 是否影子模式（只记录不拦截；此时 pass 恒 true）。 */
    private Boolean shadow;
    /** 是否发生规则降级（编译失败/执行异常/超时 → 该规则放行并告警）。 */
    private Boolean degraded;
    /** 生效规则集版本。 */
    private Integer ruleVersion;
    /** 转人工时登记的差错单 id（DisputeFacade，source=PAYMENT）。 */
    private Long disputeId;
    /** 命中的名单类型（BLACK/WHITE/GRAY），未命中为 null。 */
    private String listHit;
    /** 命中的限额规则描述（维度+周期），未命中为 null。 */
    private String quotaHit;
    /** 错误码（限额/名单/评分拒绝规范化，便于上游分类）。 */
    private String code;
}