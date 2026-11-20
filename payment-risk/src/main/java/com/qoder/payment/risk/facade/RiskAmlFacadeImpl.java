package com.qoder.payment.risk.facade;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.risk.AmlReportDTO;
import com.qoder.payment.facade.risk.AmlRuleDTO;
import com.qoder.payment.facade.risk.AmlScanResultDTO;
import com.qoder.payment.facade.risk.AmlScreeningDTO;
import com.qoder.payment.facade.risk.RiskAmlFacade;
import com.qoder.payment.risk.aml.AmlService;
import com.qoder.payment.risk.aml.AmlWatchlistService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * R-6 AML 运营 facade（控制台经网关 RiskProfileOpsController 调用）。
 * 参数/状态错误统一转 BizException（A0400/A0409），不向调用方抛异常。
 */
@DubboService
@RequiredArgsConstructor
public class RiskAmlFacadeImpl implements RiskAmlFacade {

    private final AmlService amlService;
    private final AmlWatchlistService watchlist;

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

    @Override
    public Result<List<AmlRuleDTO>> listAmlRules(String ruleType) {
        return run(() -> {
            List<AmlRuleDTO> out = new ArrayList<>();
            for (AmlRuleDTO r : amlService.rules()) {
                if (ruleType == null || ruleType.isBlank() || "ALL".equalsIgnoreCase(ruleType)
                        || ruleType.equalsIgnoreCase(r.getRuleType())) {
                    out.add(r);
                }
            }
            return out;
        });
    }

    @Override
    public Result<AmlRuleDTO> saveAmlRule(AmlRuleDTO rule, String operator) {
        return run(() -> amlService.saveRule(rule, operator));
    }

    @Override
    public Result<AmlRuleDTO> disableAmlRule(String ruleId, String operator) {
        return run(() -> amlService.disableRule(ruleId, operator));
    }

    @Override
    public Result<List<AmlReportDTO>> listAmlReports(String status, String bizDate, String payerId, Integer limit) {
        return run(() -> amlService.list(status, bizDate, payerId,
                limit == null || limit <= 0 ? 50 : Math.min(limit, 500)));
    }

    @Override
    public Result<AmlReportDTO> getAmlReport(long reportId) {
        return run(() -> amlService.get(reportId));
    }

    @Override
    public Result<AmlReportDTO> submitAmlReport(long reportId, String operator, String note) {
        return run(() -> amlService.submit(reportId, operator, note));
    }

    @Override
    public Result<AmlReportDTO> closeAmlReport(long reportId, String operator, String note) {
        return run(() -> amlService.close(reportId, operator, note));
    }

    @Override
    public Result<List<Map<String, Object>>> listAmlAudit(long reportId) {
        return run(() -> amlService.audit(reportId));
    }

    @Override
    public Result<AmlScanResultDTO> scanAml(String bizDate, String payerId) {
        return run(() -> amlService.scan(bizDate, payerId));
    }

    @Override
    public Result<List<Map<String, Object>>> listAmlTxns(String payerId, String bizDate, Integer limit) {
        return run(() -> amlService.txns(payerId, bizDate,
                limit == null || limit <= 0 ? 100 : Math.min(limit, 1000)));
    }

    @Override
    public Result<List<Map<String, Object>>> listWatchlist(String listType) {
        return run(() -> watchlist.list(listType));
    }

    @Override
    public Result<Map<String, Object>> addWatchlistEntry(String listType, String name, String aliases,
                                                         String country, String operator) {
        return run(() -> watchlist.add(listType, name, aliases, country, operator));
    }

    @Override
    public Result<Map<String, Object>> removeWatchlistEntry(String entryId, String operator) {
        return run(() -> watchlist.remove(entryId, operator));
    }

    @Override
    public Result<AmlScreeningDTO> screen(String subjectType, String subject) {
        return run(() -> {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("subject 必填");
            }
            return amlService.screen(subjectType, subject);
        });
    }

    @Override
    public Result<List<AmlScreeningDTO>> listScreenings(String subject, Integer limit) {
        return run(() -> amlService.screenings(subject, limit == null || limit <= 0 ? 50 : Math.min(limit, 500)));
    }

    @Override
    public Result<Map<String, Object>> amlStatus() {
        return run(amlService::status);
    }
}