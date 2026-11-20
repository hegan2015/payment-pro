package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-1 规则模型（QLExpress 条件 → 动作 + 风险分）。
 *
 * <p>DSL 样例：{@code amount > 500000 && txnCount(payerId, 86400000) > 10}
 * → action=REJECT, score=60。可用变量见 {@code RiskContext}，可用函数见
 * {@code RiskFunctionRegistry}（txnCount/quotaUsed/inList/deviceRisk/ipRisk/hourOfDay/amountDeviation）。
 */
@Data
public class RiskRuleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 规则 id（规则集内唯一，字符串便于配置下发）。 */
    private String id;
    private String name;
    /** 场景：PAY/REFUND/WITHDRAW/TOPUP/TRANSFER/ALL。 */
    private String scene;
    /** 优先级（小者先执行）。 */
    private Integer priority;
    /** QLExpress 布尔表达式。 */
    private String conditionExpr;
    /** 命中动作：PASS/CHALLENGE/LIMIT/REJECT/MANUAL_REVIEW。 */
    private String action;
    /** 命中贡献风险分。 */
    private Integer score;
    /** 灰度生效比例 0~100（按 payerId 哈希取模）。 */
    private Integer grayPercent;
    /** 灰度白名单（逗号分隔 payerId，恒生效，不受比例限制）。 */
    private String whitelist;
    /** 影子模式：只记录命中不拦截。 */
    private Boolean shadow;
    /** DRAFT/PUBLISHED/DISABLED。 */
    private String status;
    /** 规则版本号（每次改动 +1）。 */
    private Integer version;
    private Long createdAt;
    private Long updatedAt;
    /** 编译状态：OK / COMPILE_FAILED（护栏观测）。 */
    private String compileStatus;
    /** 编译错误摘要（compileStatus=COMPILE_FAILED 时非空）。 */
    private String compileError;
}