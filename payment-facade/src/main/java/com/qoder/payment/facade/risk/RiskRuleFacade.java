package com.qoder.payment.facade.risk;

import com.qoder.payment.common.result.Result;

import java.util.List;
import java.util.Map;

/**
 * R-1~R-4 风控运营契约（控制台经网关 RiskOpsController 调用，RBAC + 二次认证）。
 *
 * <p>与 {@link RiskFacade#check} 的关系：check 是资金链路上的受理前校验（payment 域调用，
 * 语义冻结）；本接口是运营面（规则/限额/名单/阈值 CRUD + 灰度/回滚/影子 + 留痕查询 + 试算）。
 * {@link #evaluate} 走与 check 完全相同的 pipeline，但不产生支付单，供风控策略验证。
 */
public interface RiskRuleFacade {

    // ---------- R-1 规则引擎 ----------
    /** 列出当前生效规则（含编译状态）。scene=null 返回全部。 */
    Result<List<RiskRuleDTO>> listRules(String scene);

    /** 新增/修改规则（保存即生成新规则集版本；status 缺省 DRAFT）。 */
    Result<RiskRuleDTO> saveRule(RiskRuleDTO rule, String operator);

    /** 发布规则并设置灰度比例/白名单（status=PUBLISHED）。grayPercent=null 保持原值。 */
    Result<RiskRuleDTO> publishRule(String ruleId, Integer grayPercent, String whitelist, String operator);

    /** 停用规则（status=DISABLED，不删除历史）。 */
    Result<RiskRuleDTO> disableRule(String ruleId, String operator);

    /** 影子模式开关（单规则）。 */
    Result<RiskRuleDTO> shadowMode(String ruleId, boolean on, String operator);

    /** 一键回滚规则集到上一版（恢复上一版全部规则内容与灰度/影子设置）。 */
    Result<RiskSnapshotDTO> rollbackRules(String operator);

    /** 全局影子模式开关（默认 true：新下发规则只观察不拦截）。 */
    Result<Boolean> globalShadow(boolean on, String operator);

    /** 强制从 DynamicConfig 重新拉取规则/限额/名单/阈值（local 文件等价 Apollo 推送）。 */
    Result<Map<String, Object>> reload();

    /** 引擎运行态：provider/规则集版本/影子/灰度/编译失败数/告警数/降级次数/超时配置。 */
    Result<Map<String, Object>> engineStatus();

    /** 规则集版本历史（审计 + 回滚依据）。 */
    Result<List<RiskSnapshotDTO>> listVersions(String kind, Integer limit);

    /** 试算：走完整 pipeline（名单→限额→规则→评分→处置），落留痕，不产生支付单。 */
    Result<RiskResultDTO> evaluate(RiskCheckRequest request);

    // ---------- R-2 多维限额 ----------
    Result<List<QuotaRuleDTO>> listQuotas();

    Result<QuotaRuleDTO> saveQuota(QuotaRuleDTO rule, String operator);

    Result<QuotaRuleDTO> disableQuota(String quotaId, String operator);

    Result<RiskSnapshotDTO> rollbackQuotas(String operator);

    /** 限额用量查询（dimension/scope 组合 key 前缀匹配）。 */
    Result<List<Map<String, Object>>> quotaUsage(String keyPrefix);

    // ---------- R-3 名单 ----------
    Result<List<ListEntryDTO>> listEntries(String valueType, String value);

    Result<ListEntryDTO> addListEntry(ListEntryDTO entry, String operator);

    /** 移除（软删：status=REMOVED，历史行保留可审计）。 */
    Result<ListEntryDTO> removeListEntry(String entryId, String operator);

    Result<RiskSnapshotDTO> rollbackLists(String operator);

    /** 名单命中查询（白 &gt; 黑 &gt; 灰）。 */
    Result<ListHitDTO> hitList(String valueType, String value);

    // ---------- R-4 评分与处置 ----------
    Result<RiskThresholdDTO> getThresholds(String scene);

    Result<RiskThresholdDTO> setThresholds(RiskThresholdDTO thresholds, String operator);

    Result<RiskSnapshotDTO> rollbackThresholds(String operator);

    /** 处置留痕查询（payerId 可空；limit 默认 50）。 */
    Result<List<RiskDecisionDTO>> listDecisions(String payerId, Integer limit);

    Result<RiskDecisionDTO> getDecision(long decisionId);

    /** 人审裁决：approve=true 放行（差错单核销），false 驳回（差错单 IGNORED）。 */
    Result<RiskDecisionDTO> reviewDecision(long decisionId, boolean approve, String operator);

    /** 护栏告警（规则编译失败/执行异常/超时）。 */
    Result<List<Map<String, Object>>> listAlerts(Integer limit);
}