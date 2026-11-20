package com.qoder.payment.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.dispute.DisputeFacade;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.RiskAction;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskDecisionDTO;
import com.qoder.payment.facade.risk.RiskFactorDTO;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.risk.config.RiskConfigManager;
import com.qoder.payment.risk.config.RiskKeys;
import com.qoder.payment.risk.aml.AmlService;
import com.qoder.payment.risk.device.BehaviorSequenceService;
import com.qoder.payment.risk.device.DeviceProfileService;
import com.qoder.payment.risk.device.IpProfileService;
import com.qoder.payment.risk.engine.RiskContext;
import com.qoder.payment.risk.engine.RiskRuleEngine;
import com.qoder.payment.risk.engine.RuleGrayRouter;
import com.qoder.payment.risk.list.RiskListService;
import com.qoder.payment.risk.quota.QuotaService;
import com.qoder.payment.risk.score.DispositionService;
import com.qoder.payment.risk.score.RiskScoreService;
import com.qoder.payment.risk.store.RiskStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控 pipeline（R-1~R-4）：<b>名单 → 内置保守规则（单笔限额/限频）→ 多维限额 → 规则引擎 → 评分 → 分级处置 → 留痕</b>。
 *
 * <p><b>口径冻结（§0.7 ride 红线，最高优先级）</b>：
 * <ul>
 *   <li>内置单笔限额与滑动窗口限频<b>顺序、阈值、拒绝文案逐字节不变</b>：
 *       先判 {@code payment.risk.single-limit}（默认 10000000 分）→ 再 {@code rateLimiter.tryAcquire}
 *       （{@code window-millis=10000}/{@code window-max=5}），超限不消耗窗口额度；</li>
 *   <li>规则集/限额集/名单集默认空、全局影子默认开、评分因子默认宽松
 *       → 正常 ride 流量（BALANCE 同步资金流）score=0、action=PASS、pass=true；</li>
 *   <li>拒绝一律发生在 TCC 受理之前（payment 域置 FAILED），绝不产生 PROCESSING 悬挂单。</li>
 * </ul>
 *
 * <p><b>§5.4 误配置护栏</b>：规则编译失败/执行异常/超时 → 该规则放行 + 告警（degraded=true），
 * 内置保守规则照常执行；配置 JSON 不可解析 → last-known-good。
 * <p><b>影子模式</b>：全局或单规则 shadow=true 时，规则与评分结论只记录不拦截（pass 恒 true）。
 * 名单与多维限额是显式资金约束，不受影子影响（默认空集，对正常流量零影响）。
 */
@Slf4j
@Service
public class RiskService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RateLimiter rateLimiter;
    private final DynamicConfig dynamicConfig;
    private final RiskConfigManager configs;
    private final RiskRuleEngine engine;
    private final RiskListService listService;
    private final QuotaService quotaService;
    private final RiskScoreService scoreService;
    private final DispositionService dispositionService;
    private final RiskStore store;
    /** R-5 设备指纹/IP 画像/行为序列（旁路观测 + 既有 DEVICE/IP 因子扩展）。 */
    private final DeviceProfileService deviceProfile;
    private final IpProfileService ipProfile;
    private final BehaviorSequenceService behavior;
    /** R-6 AML（默认只报告不阻断；硬规则开关默认关闭）。 */
    private final AmlService amlService;

    /** X-1 差错平台（recon 域）：转人工建单，source=PAYMENT、strategy=MANUAL（不自动动账）。 */
    @DubboReference(check = false, timeout = 5000, retries = 0)
    private DisputeFacade disputeFacade;

    public RiskService(RateLimiter rateLimiter, DynamicConfig dynamicConfig, RiskConfigManager configs,
                       RiskRuleEngine engine, RiskListService listService, QuotaService quotaService,
                       RiskScoreService scoreService, DispositionService dispositionService, RiskStore store,
                       DeviceProfileService deviceProfile, IpProfileService ipProfile,
                       BehaviorSequenceService behavior, AmlService amlService) {
        this.rateLimiter = rateLimiter;
        this.dynamicConfig = dynamicConfig;
        this.configs = configs;
        this.engine = engine;
        this.listService = listService;
        this.quotaService = quotaService;
        this.scoreService = scoreService;
        this.dispositionService = dispositionService;
        this.store = store;
        this.deviceProfile = deviceProfile;
        this.ipProfile = ipProfile;
        this.behavior = behavior;
        this.amlService = amlService;
        for (String key : new String[]{ConfigKeys.RISK_SINGLE_LIMIT,
                ConfigKeys.RISK_WINDOW_MILLIS, ConfigKeys.RISK_WINDOW_MAX}) {
            dynamicConfig.addListener(key, (k, oldV, newV) ->
                    log.info("风控配置变更 key={} {} -> {}（下次请求即生效）", k, oldV, newV));
        }
    }

    public RiskResultDTO check(RiskCheckRequest request) {
        RiskContext ctx = RiskContext.from(request);
        ctx.setRateLimiter(rateLimiter);
        ctx.setQuotaCounter(quotaService.counterForFunction());
        ctx.setListLookup(listService::inList);
        ctx.setRiskDevices(scoreService.riskDevices());
        ctx.setRiskIps(scoreService.riskIps());
        // R-5：画像观测位装载（默认 profile-score-enabled=false → 直接返回，零查询、零评分影响）
        loadProfilesQuietly(ctx);
        // R-6：AML 聚合/名单只读能力（供 QLExpress dayAmount/dayCount/sanctionHit/pepHit 函数）
        ctx.setAmlLookup(amlService.lookup());

        RiskResultDTO result = new RiskResultDTO();
        result.setScore(0);
        result.setAction(RiskAction.PASS);
        result.setShadow(false);
        result.setDegraded(false);
        result.setRuleVersion(configs.version(RiskConfigManager.RULES));

        // ---------- 1. R-3 名单（白 > 黑 > 灰） ----------
        ListEntryDTO listHit = listService.resolve(ctx);
        if (listHit != null) {
            result.setListHit(listHit.getListType());
            String action = RiskListService.actionOf(listHit);
            if ("WHITE".equals(listHit.getListType())) {
                // 白名单优先级最高：跳过限额/规则/评分，直接放行（留痕）
                result.setPass(true);
                result.setAction(RiskAction.PASS);
                result.setReason(null);
                result.setScore(0);
                result.getFactors().add(RiskFactorDTO.of("LIST", "白名单", 0,
                        listHit.getValueType() + ":" + listHit.getValue(), 0, "白名单优先于黑名单，直接放行"));
                log.info("风控放行（白名单优先）payerId={} entry={}", ctx.getPayerId(), listHit.getId());
                return finish(ctx, result);
            }
            if (RiskAction.REJECT.equals(action) || RiskAction.MANUAL_REVIEW.equals(action)) {
                result.setPass(false);
                result.setAction(action);
                result.setCode(RiskAction.CODE_LIST);
                result.setReason(RiskListService.reasonOf(listHit));
                result.getHitRules().add("LIST:" + listHit.getId());
                result.getFactors().add(RiskFactorDTO.of("LIST", "名单", 0,
                        listHit.getListType() + ":" + listHit.getValueType(), 0, result.getReason()));
                log.warn("风控拒绝（名单）payerId={} reason={}", ctx.getPayerId(), result.getReason());
                if (RiskAction.MANUAL_REVIEW.equals(action)) {
                    attachReview(ctx, result);
                }
                return finish(ctx, result);
            }
            // GRAY/CHALLENGE：不直接拒绝，进入评分（名单因子加分）后统一分级
        }

        // ---------- 2. 内置保守规则：单笔限额（口径与 P0 逐字节一致，先于限频） ----------
        long singleLimit = positive(ConfigKeys.RISK_SINGLE_LIMIT,
                dynamicConfig.getLong(ConfigKeys.RISK_SINGLE_LIMIT, ConfigKeys.DEFAULT_RISK_SINGLE_LIMIT),
                ConfigKeys.DEFAULT_RISK_SINGLE_LIMIT);
        long windowMillis = positive(ConfigKeys.RISK_WINDOW_MILLIS,
                dynamicConfig.getLong(ConfigKeys.RISK_WINDOW_MILLIS, ConfigKeys.DEFAULT_RISK_WINDOW_MILLIS),
                ConfigKeys.DEFAULT_RISK_WINDOW_MILLIS);
        int windowMax = (int) positive(ConfigKeys.RISK_WINDOW_MAX,
                dynamicConfig.getInt(ConfigKeys.RISK_WINDOW_MAX, ConfigKeys.DEFAULT_RISK_WINDOW_MAX),
                ConfigKeys.DEFAULT_RISK_WINDOW_MAX);
        if (request.getAmount() != null && request.getAmount() > singleLimit) {
            log.warn("风控拒绝 amount={} reason=超出单笔限额 {} 元", request.getAmount(), singleLimit / 100);
            result.setPass(false);
            result.setAction(RiskAction.REJECT);
            result.setCode(RiskAction.CODE_SINGLE_LIMIT);
            result.setReason("超出单笔限额 " + singleLimit / 100 + " 元");
            result.getHitRules().add("BUILTIN:SINGLE_LIMIT");
            return finish(ctx, result);
        }
        String payer = request.getPayerId() == null ? "anonymous" : request.getPayerId();
        if (!rateLimiter.tryAcquire(payer, System.currentTimeMillis(), windowMillis, windowMax)) {
            log.warn("风控拒绝 payerId={} reason=交易过于频繁", payer);
            result.setPass(false);
            result.setAction(RiskAction.REJECT);
            result.setCode(RiskAction.CODE_RATE_LIMIT);
            result.setReason("交易过于频繁，请稍后再试");
            result.getHitRules().add("BUILTIN:RATE_LIMIT");
            return finish(ctx, result);
        }

        // ---------- 3. R-2 多维限额（受理前拒绝；默认无规则=零开销） ----------
        QuotaService.Result quota = quotaService.check(ctx);
        if (!quota.pass()) {
            result.setPass(false);
            result.setAction(RiskAction.REJECT);
            result.setCode(RiskAction.CODE_QUOTA);
            result.setReason(quota.reason());
            result.setQuotaHit(quota.hit());
            result.getHitRules().add("QUOTA:" + quota.hit());
            return finish(ctx, result);
        }

        // ---------- 4. R-1 规则引擎（灰度 + 影子 + 护栏） ----------
        List<String> hitRules = result.getHitRules();
        int ruleScore = 0;
        String ruleAction = RiskAction.PASS;
        // 影子命中也累计「本应如何处置」，供观察误杀率（但不参与真实拦截）
        int observedScore = 0;
        String observedAction = RiskAction.PASS;
        boolean anyShadowHit = false;
        boolean degraded = false;
        // ---------- 4a. R-6 AML 硬规则前置（默认 hard-block-enabled=false → 直接返回 null，零开销） ----------
        // 命中硬规则才参与既有分级（DispositionService），拒绝发生在受理前，绝不落 PROCESSING 悬挂单。
        AmlService.HardHit amlHard = amlService.preCheck(ctx);
        if (amlHard != null) {
            hitRules.add("AML:" + amlHard.ruleId());
            observedScore += amlHard.score();
            if (RiskAction.severity(amlHard.action()) > RiskAction.severity(observedAction)) {
                observedAction = amlHard.action();
            }
            if (!configs.globalShadow()) {
                ruleScore += amlHard.score();
                if (RiskAction.severity(amlHard.action()) > RiskAction.severity(ruleAction)) {
                    ruleAction = amlHard.action();
                }
            } else {
                anyShadowHit = true;
            }
            log.warn("AML 硬规则命中 ruleId={} payerId={} reason={}", amlHard.ruleId(), ctx.getPayerId(),
                    amlHard.reason());
        }
        boolean engineOn = dynamicConfig.getBool(RiskKeys.ENGINE_ENABLED, RiskKeys.DEFAULT_ENGINE_ENABLED);
        if (engineOn) {
            for (RiskRuleDTO rule : configs.rules()) {
                if (!RuleGrayRouter.executable(rule) || !RuleGrayRouter.sceneMatches(rule, ctx.getScene())) {
                    continue;
                }
                if (!RuleGrayRouter.applies(rule, ctx.getPayerId())) {
                    continue;
                }
                RiskRuleEngine.Eval eval = engine.evaluate(rule, ctx);
                if (eval.degraded()) {
                    degraded = true;
                    store.alert("RULE_" + eval.errorKind(), rule.getId(), eval.error(), ctx.getTraceId());
                    log.warn("[告警] 规则护栏降级（放行）ruleId={} kind={} err={} traceId={}",
                            rule.getId(), eval.errorKind(), eval.error(), ctx.getTraceId());
                    continue;
                }
                if (!eval.hit()) {
                    continue;
                }
                boolean shadow = configs.globalShadow() || Boolean.TRUE.equals(rule.getShadow());
                hitRules.add(rule.getId() + ":" + rule.getName());
                int rs = rule.getScore() == null ? 0 : rule.getScore();
                observedScore += rs;
                if (RiskAction.severity(rule.getAction()) > RiskAction.severity(observedAction)) {
                    observedAction = rule.getAction();
                }
                if (shadow) {
                    anyShadowHit = true;
                    log.info("规则影子命中（只记录不拦截）ruleId={} payerId={} score={} wouldBe={}",
                            rule.getId(), ctx.getPayerId(), rs, rule.getAction());
                    continue;
                }
                ruleScore += rs;
                if (RiskAction.severity(rule.getAction()) > RiskAction.severity(ruleAction)) {
                    ruleAction = rule.getAction();
                }
            }
        }
        result.setDegraded(degraded);

        // ---------- 5. R-4 评分 ----------
        boolean scoreOn = dynamicConfig.getBool(RiskKeys.SCORE_ENABLED, RiskKeys.DEFAULT_SCORE_ENABLED);
        boolean globalShadow = configs.globalShadow();
        int effectiveRuleScore = globalShadow ? observedScore : ruleScore;
        String effectiveRuleAction = globalShadow ? observedAction : ruleAction;
        int total = 0;
        List<RiskFactorDTO> factors = result.getFactors();
        if (scoreOn) {
            if (scoreService.amountHistoryEnabled()) {
                quotaService.recordScoreUsage(ctx.getPayerId(), ctx.getNow(), ctx.getAmount());
            }
            RiskScoreService.Score s = scoreService.score(ctx, listHit, effectiveRuleScore, hitRules);
            total = s.total();
            factors.addAll(s.factors());
        }
        result.setScore(total);

        // ---------- 6. R-4 分级处置 ----------
        DispositionService.Disposition d = dispositionService.decide(ctx.getScene(), total, ctx.getAmount(),
                effectiveRuleAction);
        // 全局影子：规则 + 评分结论只记录不拦截；灰名单是显式名单决策，不受影子影响
        boolean explicitList = listHit != null && "GRAY".equals(listHit.getListType());
        boolean shadowApplied = globalShadow && !explicitList;
        if (shadowApplied) {
            result.setPass(true);
            result.setAction(d.action());
            result.setShadow(true);
            result.setReason(null);
            result.setCode(null);
        } else {
            result.setPass(d.pass());
            result.setAction(d.action());
            result.setShadow(anyShadowHit && d.pass());
            if (!d.pass()) {
                result.setCode(d.code());
                result.setReason(d.reason());
            }
        }
        if (RiskAction.MANUAL_REVIEW.equals(result.getAction()) && !Boolean.TRUE.equals(result.getPass())) {
            attachReview(ctx, result);
        }
        if (Boolean.TRUE.equals(result.getPass())) {
            log.info("风控通过 payerId={} amount={} method={} score={} action={} shadow={} degraded={}",
                    payer, request.getAmount(), request.getMethod(), total, result.getAction(),
                    result.getShadow(), degraded);
        } else {
            log.warn("风控拒绝 payerId={} amount={} score={} action={} reason={}",
                    payer, request.getAmount(), total, result.getAction(), result.getReason());
        }
        return finish(ctx, result);
    }

    /** R-4 转人工：差错平台建单（source=PAYMENT，strategy=MANUAL 不自动动账）+ 人审工单。 */
    private void attachReview(RiskContext ctx, RiskResultDTO result) {
        Long decisionId = null;
        Long disputeId = null;
        try {
            if (disputeFacade != null) {
                DisputeCreateRequest req = new DisputeCreateRequest();
                req.setType("RISK_MANUAL_REVIEW");
                req.setSource("PAYMENT");
                req.setBizNo(ctx.getBizNo() == null || ctx.getBizNo().isBlank()
                        ? "RISK:" + ctx.getPayerId() + ":" + ctx.getNow() : ctx.getBizNo());
                req.setAmount(ctx.getAmount());
                req.setStrategy("MANUAL");
                req.setReason("风控转人工：score=" + result.getScore() + " action=" + result.getAction()
                        + " payer=" + ctx.getPayerId());
                var r = disputeFacade.create(req);
                if (r != null && r.isSuccess() && r.getData() != null) {
                    DisputeDTO dto = r.getData();
                    disputeId = dto.getId();
                }
            }
        } catch (Exception e) {
            // 差错平台不可用不阻断风控结论（人审工单仍落本地，可后续补建）
            log.warn("转人工建单失败（本地人审工单仍留痕）: {}", e.getMessage());
            store.alert("DISPUTE_CREATE_FAIL", null, e.getMessage(), ctx.getTraceId());
        }
        result.setDisputeId(disputeId);
        result.setCode(RiskAction.CODE_SCORE);
        MetricsSupport.count("risk.manual.review");
    }

    /** 落留痕（失败不阻断）+ 人审工单。 */
    private RiskResultDTO finish(RiskContext ctx, RiskResultDTO result) {
        RiskDecisionDTO d = new RiskDecisionDTO();
        d.setPayerId(ctx.getPayerId());
        d.setMerchantId(ctx.getMerchantId());
        d.setAmount(ctx.getAmount());
        d.setScene(ctx.getScene());
        d.setMethod(ctx.getMethod());
        d.setChannelCode(ctx.getChannelCode());
        d.setCardMask(mask(ctx.getCardNo()));
        d.setDeviceId(ctx.getDeviceId());
        d.setIp(ctx.getIp());
        d.setScore(result.getScore());
        d.setAction(result.getAction());
        d.setPass(result.getPass());
        d.setReason(result.getReason());
        d.setCode(result.getCode());
        d.setHitRules(json(result.getHitRules()));
        d.setFactors(json(result.getFactors()));
        d.setShadow(result.getShadow());
        d.setDegraded(result.getDegraded());
        d.setRuleVersion(result.getRuleVersion());
        d.setListHit(result.getListHit());
        d.setQuotaHit(result.getQuotaHit());
        d.setTraceId(ctx.getTraceId());
        d.setBizNo(ctx.getBizNo());
        d.setDisputeId(result.getDisputeId());
        Long id = store.insertDecision(d);
        result.setDecisionId(id);
        if (id != null && RiskAction.MANUAL_REVIEW.equals(result.getAction())
                && !Boolean.TRUE.equals(result.getPass())) {
            store.insertReview(id, ctx.getPayerId(), ctx.getAmount(), result.getDisputeId());
        }
        MetricsSupport.count("risk.check", "action", result.getAction() == null ? "PASS" : result.getAction(),
                "pass", String.valueOf(result.getPass()));
        // R-5/R-6 旁路观测（画像采集 + AML 流水/筛查/实时报告）：失败只 warn，绝不阻断交易
        observeQuietly(ctx, result);
        return result;
    }

    /** R-5 画像观测位装载：任何异常按「无画像信息」处理（不加分、不拦截）。 */
    private void loadProfilesQuietly(RiskContext ctx) {
        try {
            deviceProfile.loadInto(ctx);
        } catch (Exception e) {
            log.warn("设备画像装载失败（按无信息处理，不加分）: {}", e.getMessage());
        }
        try {
            ipProfile.loadInto(ctx);
        } catch (Exception e) {
            log.warn("IP 画像装载失败（按无信息处理，不加分）: {}", e.getMessage());
        }
    }

    /** R-5/R-6 旁路观测：deviceId/ip 全空时三个服务各自直接跳过（ride/smoke 真实支付即此路径）。 */
    private void observeQuietly(RiskContext ctx, RiskResultDTO result) {
        try {
            behavior.observe(ctx);
        } catch (Exception e) {
            log.warn("行为序列观测失败（不阻断交易）: {}", e.getMessage());
        }
        try {
            deviceProfile.observe(ctx);
        } catch (Exception e) {
            log.warn("设备画像观测失败（不阻断交易）: {}", e.getMessage());
        }
        try {
            ipProfile.observe(ctx, result);
        } catch (Exception e) {
            log.warn("IP 画像观测失败（不阻断交易）: {}", e.getMessage());
        }
        try {
            amlService.observe(ctx, result);
        } catch (Exception e) {
            log.warn("AML 观测失败（不阻断交易）: {}", e.getMessage());
        }
    }

    private static String mask(String cardNo) {
        if (cardNo == null || cardNo.length() < 10) {
            return cardNo;
        }
        return cardNo.substring(0, 6) + "******" + cardNo.substring(cardNo.length() - 4);
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 非法配置值（<=0）防误配兜底：回退默认值，避免配置手误阻断全部支付。 */
    private static long positive(String key, long value, long def) {
        if (value <= 0) {
            log.warn("风控配置非法 key={} value={}，用默认值 {}", key, value, def);
            return def;
        }
        return value;
    }

    /** 人审裁决（R-4）：approve → 差错单核销；reject → 差错单驳回。 */
    public RiskDecisionDTO review(long decisionId, boolean approve, String operator) {
        RiskDecisionDTO d = store.getDecision(decisionId);
        if (d == null) {
            throw new IllegalArgumentException("处置留痕不存在: " + decisionId);
        }
        if (!RiskAction.MANUAL_REVIEW.equals(d.getAction())) {
            throw new IllegalStateException("该处置非转人工，无需人审: " + d.getAction());
        }
        var existing = store.reviewByDecision(decisionId);
        if (existing != null && !"PENDING".equals(existing.get("status"))) {
            throw new IllegalStateException("人审已裁决: " + existing.get("status"));
        }
        store.finishReview(decisionId, approve ? "APPROVED" : "REJECTED", operator);
        if (d.getDisputeId() != null && disputeFacade != null) {
            try {
                if (approve) {
                    disputeFacade.resolve(d.getDisputeId(), operator, "风控人审通过");
                } else {
                    disputeFacade.reject(d.getDisputeId(), operator, "风控人审驳回");
                }
            } catch (Exception e) {
                log.warn("人审同步差错平台失败（本地裁决已留痕）disputeId={}: {}", d.getDisputeId(), e.getMessage());
            }
        }
        return store.getDecision(decisionId);
    }

    /** 供控制台观测：当前生效处置留痕条数等。 */
    public List<RiskDecisionDTO> decisions(String payerId, int limit) {
        return store.listDecisions(payerId, limit);
    }
}