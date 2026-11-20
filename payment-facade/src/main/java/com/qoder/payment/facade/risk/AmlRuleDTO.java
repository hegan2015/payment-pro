package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-6 AML 监测规则。
 *
 * <p>{@code ruleType} 为内置评估器类型：LARGE_AMOUNT（大额）/ SPLIT（拆分·分散转入集中转出）/
 * STRUCTURING（结构化·贴近阈值多笔）/ FAST_IN_OUT（快进快出）/ ABNORMAL_HOUR（异常时段）/
 * CROSS_BORDER（跨境）/ SANCTION（制裁名单）/ PEP（政要名单）/ CUSTOM（纯 QLExpress）。
 * <p>{@code conditionExpr} 非空时经既有 {@code RiskRuleEngine}（QLExpress）评估，
 * <b>编译失败/执行异常/超时一律跳过并告警</b>（继承 P1-B 护栏，绝不因规则错误拦截交易）。
 * <p>{@code hardRule=true} 且 {@code risk.aml.hard-block-enabled=true}（默认 false）才在受理前拒绝；
 * 其余一律「只生成可疑报告，不阻断正常交易」。
 */
@Data
public class AmlRuleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String ruleType;
    private String conditionExpr;
    /** 金额阈值（分）。 */
    private Long threshold;
    /** 聚合窗口（毫秒）；0/空=按业务日聚合。 */
    private Long windowMillis;
    /** 笔数下限。 */
    private Integer minCount;
    /** 比例阈值（百分数，如 90 表示 90%）。 */
    private Integer ratio;
    /** 命中贡献分（接入既有 DispositionService 分级，不另起体系）。 */
    private Integer score;
    /** 是否硬规则（默认 false）。 */
    private Boolean hardRule;
    /** 是否实时评估（单笔类规则 true；聚合类由监测调度扫描）。 */
    private Boolean realtime;
    /** ENABLED / DISABLED。 */
    private String status;
    private Integer version;
    private String operator;
    /** 编译状态：OK / COMPILE_FAILED / BUILTIN。 */
    private String compileStatus;
    private String compileError;
    private Long createdAt;
    private Long updatedAt;
}