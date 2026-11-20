package com.qoder.payment.facade.risk;

import com.qoder.payment.common.result.Result;

import java.util.List;
import java.util.Map;

/**
 * R-6 反洗钱（AML）运营契约（<b>新增接口，additive</b>；不改既有 {@link RiskRuleFacade}）。
 *
 * <p>控制台经网关 {@code RiskProfileOpsController}（{@code /api/risk/aml/**}）调用。
 * <p><b>不阻断红线</b>：AML 侧重监测报告，命中默认只生成 STR 并走既有 {@code DispositionService}
 * 分级 + {@code DisputeFacade} 人工建单（strategy=MANUAL 不动账）；只有
 * {@code hardRule=true} 且 {@code risk.aml.hard-block-enabled=true}（默认 false）才在受理前拒绝。
 */
public interface RiskAmlFacade {

    // ---------- 规则 ----------
    Result<List<AmlRuleDTO>> listAmlRules(String ruleType);

    Result<AmlRuleDTO> saveAmlRule(AmlRuleDTO rule, String operator);

    Result<AmlRuleDTO> disableAmlRule(String ruleId, String operator);

    // ---------- 报告与状态机 ----------
    Result<List<AmlReportDTO>> listAmlReports(String status, String bizDate, String payerId, Integer limit);

    Result<AmlReportDTO> getAmlReport(long reportId);

    /** 上报：PENDING → SUBMITTED（写审计；重复上报返回冲突）。 */
    Result<AmlReportDTO> submitAmlReport(long reportId, String operator, String note);

    /** 归档：SUBMITTED → CLOSED，或 PENDING → CLOSED（排除误报）。 */
    Result<AmlReportDTO> closeAmlReport(long reportId, String operator, String note);

    /** 状态流转审计（from/to/operator/note/时间）。 */
    Result<List<Map<String, Object>>> listAmlAudit(long reportId);

    // ---------- 监测 ----------
    /** 手动/调度触发扫描（与 AmlMonitorJob 同一代码路径，幂等）。 */
    Result<AmlScanResultDTO> scanAml(String bizDate, String payerId);

    /** 交易流水观测（AML 自有镜像，报告引用与流水一致可核对）。 */
    Result<List<Map<String, Object>>> listAmlTxns(String payerId, String bizDate, Integer limit);

    // ---------- 名单筛查（沙箱制裁/PEP） ----------
    Result<List<Map<String, Object>>> listWatchlist(String listType);

    Result<Map<String, Object>> addWatchlistEntry(String listType, String name, String aliases,
                                                  String country, String operator);

    /** 软删（status=REMOVED，历史行保留可审计）。 */
    Result<Map<String, Object>> removeWatchlistEntry(String entryId, String operator);

    /** 单对象筛查（命中与否都留痕）。 */
    Result<AmlScreeningDTO> screen(String subjectType, String subject);

    Result<List<AmlScreeningDTO>> listScreenings(String subject, Integer limit);

    /** AML 运行态：开关 / 阈值 / 规则数 / 报告数 / 硬拦截开关 / 留存期 / 护栏计数。 */
    Result<Map<String, Object>> amlStatus();
}