package com.qoder.payment.risk.facade;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.ListHitDTO;
import com.qoder.payment.facade.risk.QuotaRuleDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskDecisionDTO;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.facade.risk.RiskRuleFacade;
import com.qoder.payment.facade.risk.RiskSnapshotDTO;
import com.qoder.payment.facade.risk.RiskThresholdDTO;
import com.qoder.payment.risk.config.RiskConfigManager;
import com.qoder.payment.risk.list.RiskListService;
import com.qoder.payment.risk.quota.QuotaService;
import com.qoder.payment.risk.service.RiskService;
import com.qoder.payment.risk.store.RiskStore;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * R-1~R-4 风控运营 facade（控制台经网关 RiskOpsController 调用）。
 * 参数/状态错误统一转 BizException（A0400/A0409），不向调用方抛异常。
 */
@DubboService
@RequiredArgsConstructor
public class RiskRuleFacadeImpl implements RiskRuleFacade {

    private final RiskConfigManager configs;
    private final RiskService riskService;
    private final RiskListService listService;
    private final QuotaService quotaService;
    private final RiskStore store;

    private static <T> Result<T> run(Supplier<T> action) {
        return FacadeSupport.execute(() -> {
            try {
                return action.get();
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
            } catch (IllegalStateException e) {
                throw new BizException(ResultCode.CONFLICT, e.getMessage());
            }
        });
    }

    // ---------- R-1 ----------

    @Override
    public Result<List<RiskRuleDTO>> listRules(String scene) {
        return run(() -> {
            List<RiskRuleDTO> out = new ArrayList<>();
            for (RiskRuleDTO r : configs.rules()) {
                if (scene == null || scene.isBlank() || "ALL".equalsIgnoreCase(scene)
                        || scene.equalsIgnoreCase(r.getScene()) || "ALL".equalsIgnoreCase(r.getScene())) {
                    out.add(r);
                }
            }
            return out;
        });
    }

    @Override
    public Result<RiskRuleDTO> saveRule(RiskRuleDTO rule, String operator) {
        return run(() -> {
            if (rule == null || rule.getConditionExpr() == null || rule.getConditionExpr().isBlank()) {
                throw new IllegalArgumentException("conditionExpr 必填");
            }
            configs.saveRule(rule, operator);
            return findRule(rule.getId());
        });
    }

    @Override
    public Result<RiskRuleDTO> publishRule(String ruleId, Integer grayPercent, String whitelist, String operator) {
        return run(() -> {
            configs.publishRule(ruleId, grayPercent, whitelist, operator);
            return findRule(ruleId);
        });
    }

    @Override
    public Result<RiskRuleDTO> disableRule(String ruleId, String operator) {
        return run(() -> {
            configs.disableRule(ruleId, operator);
            return findRule(ruleId);
        });
    }

    @Override
    public Result<RiskRuleDTO> shadowMode(String ruleId, boolean on, String operator) {
        return run(() -> {
            configs.shadowRule(ruleId, on, operator);
            return findRule(ruleId);
        });
    }

    @Override
    public Result<RiskSnapshotDTO> rollbackRules(String operator) {
        return run(() -> configs.rollback(RiskConfigManager.RULES, operator));
    }

    @Override
    public Result<Boolean> globalShadow(boolean on, String operator) {
        return run(() -> {
            configs.setGlobalShadow(on, operator);
            return configs.globalShadow();
        });
    }

    @Override
    public Result<Map<String, Object>> reload() {
        return run(configs::reload);
    }

    @Override
    public Result<Map<String, Object>> engineStatus() {
        return run(() -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(configs.status());
            m.put("quotaProvider", quotaService.provider());
            m.put("thresholds", configs.allThresholds());
            return m;
        });
    }

    @Override
    public Result<List<RiskSnapshotDTO>> listVersions(String kind, Integer limit) {
        return run(() -> store.versions(kind == null || kind.isBlank() ? RiskConfigManager.RULES
                : kind.trim().toUpperCase(), limit == null || limit <= 0 ? 20 : Math.min(limit, 200)));
    }

    @Override
    public Result<RiskResultDTO> evaluate(RiskCheckRequest request) {
        return run(() -> riskService.check(request));
    }

    private RiskRuleDTO findRule(String id) {
        for (RiskRuleDTO r : configs.rules()) {
            if (r.getId().equals(id)) {
                return r;
            }
        }
        throw new IllegalArgumentException("规则不存在: " + id);
    }

    // ---------- R-2 ----------

    @Override
    public Result<List<QuotaRuleDTO>> listQuotas() {
        return run(configs::quotas);
    }

    @Override
    public Result<QuotaRuleDTO> saveQuota(QuotaRuleDTO rule, String operator) {
        return run(() -> {
            if (rule == null || rule.getDimensions() == null || rule.getPeriod() == null) {
                throw new IllegalArgumentException("dimensions/period 必填");
            }
            configs.saveQuota(rule, operator);
            for (QuotaRuleDTO q : configs.quotas()) {
                if (q.getId().equals(rule.getId())) {
                    return q;
                }
            }
            throw new IllegalArgumentException("限额规则非法被跳过（维度/周期取值不合法）: " + rule.getId());
        });
    }

    @Override
    public Result<QuotaRuleDTO> disableQuota(String quotaId, String operator) {
        return run(() -> {
            configs.disableQuota(quotaId, operator);
            for (QuotaRuleDTO q : configs.quotas()) {
                if (q.getId().equals(quotaId)) {
                    return q;
                }
            }
            throw new IllegalArgumentException("限额规则不存在: " + quotaId);
        });
    }

    @Override
    public Result<RiskSnapshotDTO> rollbackQuotas(String operator) {
        return run(() -> configs.rollback(RiskConfigManager.QUOTAS, operator));
    }

    @Override
    public Result<List<Map<String, Object>>> quotaUsage(String keyPrefix) {
        return run(() -> store.listUsage(keyPrefix));
    }

    // ---------- R-3 ----------

    @Override
    public Result<List<ListEntryDTO>> listEntries(String valueType, String value) {
        return run(() -> {
            List<ListEntryDTO> out = new ArrayList<>();
            for (ListEntryDTO e : configs.lists()) {
                if (valueType != null && !valueType.isBlank()
                        && !valueType.equalsIgnoreCase(e.getValueType())) {
                    continue;
                }
                if (value != null && !value.isBlank() && !value.equals(e.getValue())) {
                    continue;
                }
                out.add(e);
            }
            return out;
        });
    }

    @Override
    public Result<ListEntryDTO> addListEntry(ListEntryDTO entry, String operator) {
        return run(() -> {
            if (entry == null || entry.getListType() == null || entry.getValueType() == null
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("listType/valueType/value 必填");
            }
            configs.addListEntry(entry, operator);
            for (ListEntryDTO e : configs.lists()) {
                if (e.getValueType().equalsIgnoreCase(entry.getValueType())
                        && e.getValue().equals(entry.getValue().trim())) {
                    return e;
                }
            }
            throw new IllegalArgumentException("名单条目非法被跳过: " + entry.getValue());
        });
    }

    @Override
    public Result<ListEntryDTO> removeListEntry(String entryId, String operator) {
        return run(() -> {
            configs.removeListEntry(entryId, operator);
            for (ListEntryDTO e : configs.lists()) {
                if (e.getId().equals(entryId)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("名单条目不存在: " + entryId);
        });
    }

    @Override
    public Result<RiskSnapshotDTO> rollbackLists(String operator) {
        return run(() -> configs.rollback(RiskConfigManager.LISTS, operator));
    }

    @Override
    public Result<ListHitDTO> hitList(String valueType, String value) {
        return run(() -> listService.hit(valueType, value));
    }

    // ---------- R-4 ----------

    @Override
    public Result<RiskThresholdDTO> getThresholds(String scene) {
        return run(() -> configs.thresholds(scene));
    }

    @Override
    public Result<RiskThresholdDTO> setThresholds(RiskThresholdDTO thresholds, String operator) {
        return run(() -> {
            if (thresholds == null) {
                throw new IllegalArgumentException("thresholds 必填");
            }
            configs.setThresholds(thresholds, operator);
            return configs.thresholds(thresholds.getScene());
        });
    }

    @Override
    public Result<RiskSnapshotDTO> rollbackThresholds(String operator) {
        return run(() -> configs.rollback(RiskConfigManager.THRESHOLDS, operator));
    }

    @Override
    public Result<List<RiskDecisionDTO>> listDecisions(String payerId, Integer limit) {
        return run(() -> store.listDecisions(payerId, limit == null || limit <= 0 ? 50 : Math.min(limit, 500)));
    }

    @Override
    public Result<RiskDecisionDTO> getDecision(long decisionId) {
        return run(() -> {
            RiskDecisionDTO d = store.getDecision(decisionId);
            if (d == null) {
                throw new IllegalArgumentException("处置留痕不存在: " + decisionId);
            }
            return d;
        });
    }

    @Override
    public Result<RiskDecisionDTO> reviewDecision(long decisionId, boolean approve, String operator) {
        return run(() -> riskService.review(decisionId, approve, operator));
    }

    @Override
    public Result<List<Map<String, Object>>> listAlerts(Integer limit) {
        return run(() -> store.listAlerts(limit == null || limit <= 0 ? 50 : Math.min(limit, 500)));
    }
}