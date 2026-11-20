package com.qoder.payment.risk.aml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.dispute.DisputeFacade;
import com.qoder.payment.facade.risk.AmlReportDTO;
import com.qoder.payment.facade.risk.AmlRuleDTO;
import com.qoder.payment.facade.risk.AmlScanResultDTO;
import com.qoder.payment.facade.risk.AmlScreeningDTO;
import com.qoder.payment.facade.risk.RiskAction;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.risk.config.RiskPlusKeys;
import com.qoder.payment.risk.device.DeviceProfileService;
import com.qoder.payment.risk.device.IpProfileService;
import com.qoder.payment.risk.device.SandboxIpDataSource;
import com.qoder.payment.risk.engine.RiskContext;
import com.qoder.payment.risk.engine.RiskRuleEngine;
import com.qoder.payment.risk.score.DispositionService;
import com.qoder.payment.risk.store.RiskStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * R-6 反洗钱（AML）：可疑交易监测 → 可疑报告（STR）→ 状态机 → 审计。
 *
 * <p><b>不阻断红线（最高优先级）</b>：AML 侧重<b>监测报告</b>，命中默认只生成 STR，
 * 经既有 {@link DispositionService} 分级 + {@link DisputeFacade} 人工建单
 * （source=PAYMENT、type=AML_STR、strategy=MANUAL，<b>不自动动账</b>）；
 * 只有 {@code hardRule=true} 且 {@code risk.aml.hard-block-enabled=true}（<b>默认 false</b>）
 * 才在受理前拒绝（此时 payment 域置 FAILED，绝不落 PROCESSING 悬挂单）。
 *
 * <p><b>护栏</b>：自定义表达式经既有 {@link RiskRuleEngine}（QLExpress）执行，
 * 编译失败/执行异常/超时一律跳过 + 告警；配置 JSON 不可解析保留 last-known-good；
 * 观测/报告/建单任何异常只 warn，<b>绝不因 AML 自身故障拦截正常交易</b>。
 *
 * <p><b>幂等</b>：报告唯一键 {@code (payerId, ruleId, bizDate)}，重复监测/重复扫描复用既有报告。
 * <p><b>双 profile</b>：local H2 + 内置沙箱名单/离线 IP 表；docker MySQL + Apollo 配置，
 * 监测调度经 I-3 {@code JobHandler}（local @Scheduled 兜底 / docker XXL-Job 可接管）。
 */
@Slf4j
@Service
public class AmlService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AmlStore store;
    private final AmlRuleEngine engine;
    private final AmlWatchlistService watchlist;
    private final DynamicConfig config;
    private final DispositionService dispositionService;
    private final IpProfileService ipProfile;
    private final DeviceProfileService deviceProfile;
    private final RiskStore riskStore;

    /** X-1 差错平台（recon 域）：STR 达 human-review 档建单，strategy=MANUAL 不动账。 */
    @DubboReference(check = false, timeout = 5000, retries = 0)
    private DisputeFacade disputeFacade;

    private volatile String lastRaw = "";
    private final AtomicLong degradedCount = new AtomicLong();
    private final AtomicLong reportCreated = new AtomicLong();
    private final AtomicLong reportReused = new AtomicLong();

    public AmlService(AmlStore store, AmlRuleEngine engine, AmlWatchlistService watchlist, DynamicConfig config,
                      DispositionService dispositionService, IpProfileService ipProfile,
                      DeviceProfileService deviceProfile, RiskStore riskStore) {
        this.store = store;
        this.engine = engine;
        this.watchlist = watchlist;
        this.config = config;
        this.dispositionService = dispositionService;
        this.ipProfile = ipProfile;
        this.deviceProfile = deviceProfile;
        this.riskStore = riskStore;
    }

    /** 硬规则前置命中（默认关闭；命中才参与既有分级处置）。 */
    public record HardHit(String ruleId, String action, int score, String reason) {
    }

    // ---------------- 开关与阈值 ----------------

    public boolean enabled() {
        return config.getBool(RiskPlusKeys.AML_ENABLED, RiskPlusKeys.DEFAULT_AML_ENABLED);
    }

    public boolean hardBlockEnabled() {
        return config.getBool(RiskPlusKeys.AML_HARD_BLOCK, RiskPlusKeys.DEFAULT_AML_HARD_BLOCK);
    }

    public boolean scoreEnabled() {
        return config.getBool(RiskPlusKeys.AML_SCORE_ENABLED, RiskPlusKeys.DEFAULT_AML_SCORE_ENABLED);
    }

    public boolean screeningEnabled() {
        return config.getBool(RiskPlusKeys.AML_SCREENING, RiskPlusKeys.DEFAULT_AML_SCREENING);
    }

    public boolean disputeEnabled() {
        return config.getBool(RiskPlusKeys.AML_DISPUTE, RiskPlusKeys.DEFAULT_AML_DISPUTE);
    }

    public int retentionDays() {
        int v = config.getInt(RiskPlusKeys.AML_RETENTION_DAYS, RiskPlusKeys.DEFAULT_AML_RETENTION_DAYS);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_AML_RETENTION_DAYS;
    }

    public long largeAmount() {
        return positive(config.getLong(RiskPlusKeys.AML_LARGE_AMOUNT, RiskPlusKeys.DEFAULT_AML_LARGE_AMOUNT),
                RiskPlusKeys.DEFAULT_AML_LARGE_AMOUNT);
    }

    public long dayAmount() {
        return positive(config.getLong(RiskPlusKeys.AML_DAY_AMOUNT, RiskPlusKeys.DEFAULT_AML_DAY_AMOUNT),
                RiskPlusKeys.DEFAULT_AML_DAY_AMOUNT);
    }

    public int splitCount() {
        return (int) positive(config.getInt(RiskPlusKeys.AML_SPLIT_COUNT, RiskPlusKeys.DEFAULT_AML_SPLIT_COUNT),
                RiskPlusKeys.DEFAULT_AML_SPLIT_COUNT);
    }

    public int structuringCount() {
        return (int) positive(config.getInt(RiskPlusKeys.AML_STRUCTURING_COUNT,
                RiskPlusKeys.DEFAULT_AML_STRUCTURING_COUNT), RiskPlusKeys.DEFAULT_AML_STRUCTURING_COUNT);
    }

    public int structuringRatio() {
        return (int) positive(config.getInt(RiskPlusKeys.AML_STRUCTURING_RATIO,
                RiskPlusKeys.DEFAULT_AML_STRUCTURING_RATIO), RiskPlusKeys.DEFAULT_AML_STRUCTURING_RATIO);
    }

    public long fastWindow() {
        return positive(config.getLong(RiskPlusKeys.AML_FAST_WINDOW, RiskPlusKeys.DEFAULT_AML_FAST_WINDOW),
                RiskPlusKeys.DEFAULT_AML_FAST_WINDOW);
    }

    public int fastRatio() {
        return (int) positive(config.getInt(RiskPlusKeys.AML_FAST_RATIO, RiskPlusKeys.DEFAULT_AML_FAST_RATIO),
                RiskPlusKeys.DEFAULT_AML_FAST_RATIO);
    }

    public long nightAmount() {
        return positive(config.getLong(RiskPlusKeys.AML_NIGHT_AMOUNT, RiskPlusKeys.DEFAULT_AML_NIGHT_AMOUNT),
                RiskPlusKeys.DEFAULT_AML_NIGHT_AMOUNT);
    }

    public long crossBorderAmount() {
        return positive(config.getLong(RiskPlusKeys.AML_CROSS_BORDER_AMOUNT,
                RiskPlusKeys.DEFAULT_AML_CROSS_BORDER_AMOUNT), RiskPlusKeys.DEFAULT_AML_CROSS_BORDER_AMOUNT);
    }

    public Set<Integer> nightHours() {
        String raw = config.get(RiskPlusKeys.AML_NIGHT_HOURS, RiskPlusKeys.DEFAULT_AML_NIGHT_HOURS);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
                return -1;
            }
        }).filter(h -> h >= 0 && h <= 23).collect(Collectors.toSet());
    }

    private static long positive(long v, long def) {
        return v > 0 ? v : def;
    }

    public static String bizDate(long epochMillis) {
        return DAY.format(Instant.ofEpochMilli(epochMillis).atZone(ZONE));
    }

    /** 资金方向：TOPUP/REFUND 入账，其余出账（快进快出判定用）。 */
    public static String direction(String scene) {
        String s = scene == null ? "PAY" : scene.trim().toUpperCase();
        return switch (s) {
            case "TOPUP", "REFUND" -> "IN";
            default -> "OUT";
        };
    }

    // ---------------- 规则装载（DB 权威 + 配置覆盖 + last-known-good） ----------------

    @PostConstruct
    public void seed() {
        try {
            if (store.ruleCount() == 0) {
                for (AmlRuleDTO r : defaultRules()) {
                    store.saveRule(r);
                }
                log.info("R-6 AML 内置规则 seed 完成 count={}", store.ruleCount());
            }
            applyConfigIfChanged();
        } catch (Exception e) {
            log.warn("R-6 AML 规则初始化失败（监测降级为不命中，不阻断交易）: {}", e.getMessage());
        }
    }

    /** 内置默认规则（全部 hardRule=false → 默认只报告不拦截）。 */
    public List<AmlRuleDTO> defaultRules() {
        long now = System.currentTimeMillis();
        List<AmlRuleDTO> out = new ArrayList<>();
        out.add(rule("AML_LARGE", "大额交易监测", "LARGE_AMOUNT", largeAmount(), null, 1, null, 60, true, now));
        out.add(rule("AML_SPLIT", "拆分交易（分散转入集中转出）", "SPLIT", dayAmount(), null, splitCount(), null,
                70, false, now));
        out.add(rule("AML_STRUCT", "结构化交易（贴近阈值多笔）", "STRUCTURING", largeAmount(), null,
                structuringCount(), structuringRatio(), 70, false, now));
        out.add(rule("AML_FAST", "快进快出", "FAST_IN_OUT", nightAmount(), fastWindow(), 1, fastRatio(), 80,
                false, now));
        out.add(rule("AML_NIGHT", "异常时段交易", "ABNORMAL_HOUR", nightAmount(), null, 1, null, 50, true, now));
        out.add(rule("AML_CROSS", "跨境交易", "CROSS_BORDER", crossBorderAmount(), null, 1, null, 60, true, now));
        out.add(rule("AML_SANCTION", "制裁名单命中", "SANCTION", null, null, 1, null, 100, true, now));
        out.add(rule("AML_PEP", "政要（PEP）名单命中", "PEP", null, null, 1, null, 90, true, now));
        return out;
    }

    private static AmlRuleDTO rule(String id, String name, String type, Long threshold, Long window, int minCount,
                                   Integer ratio, int score, boolean realtime, long now) {
        AmlRuleDTO d = new AmlRuleDTO();
        d.setId(id);
        d.setName(name);
        d.setRuleType(type);
        d.setThreshold(threshold);
        d.setWindowMillis(window);
        d.setMinCount(minCount);
        d.setRatio(ratio);
        d.setScore(score);
        d.setHardRule(false);
        d.setRealtime(realtime);
        d.setStatus("ENABLED");
        d.setVersion(1);
        d.setOperator("system-seed");
        d.setCompileStatus("BUILTIN");
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    /** 配置覆盖（热更）：JSON 数组 upsert；解析失败保留 last-known-good + 告警。 */
    private void applyConfigIfChanged() {
        String raw = config.get(RiskPlusKeys.AML_RULES_JSON, null);
        String key = raw == null ? "" : raw;
        if (key.equals(lastRaw)) {
            return;
        }
        lastRaw = key;
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            List<AmlRuleDTO> parsed = MAPPER.readValue(raw,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, AmlRuleDTO.class));
            for (AmlRuleDTO r : parsed) {
                if (r.getId() == null || r.getId().isBlank() || r.getRuleType() == null) {
                    continue;
                }
                normalize(r, "config-center");
                store.saveRule(r);
            }
            log.info("R-6 AML 规则配置覆盖生效 count={}", parsed.size());
        } catch (Exception e) {
            degradedCount.incrementAndGet();
            riskStore.alert("AML_CONFIG_PARSE_FAIL", null, "AML 规则 JSON 解析失败，保留 last-known-good", null);
            log.warn("R-6 AML 规则配置解析失败（保留 last-known-good）: {}", e.getMessage());
        }
    }

    private void normalize(AmlRuleDTO r, String operator) {
        long now = System.currentTimeMillis();
        AmlRuleDTO old = store.getRule(r.getId());
        if (r.getName() == null || r.getName().isBlank()) {
            r.setName(old != null ? old.getName() : r.getId());
        }
        r.setRuleType(r.getRuleType().trim().toUpperCase());
        if (r.getStatus() == null || r.getStatus().isBlank()) {
            r.setStatus("ENABLED");
        }
        r.setStatus(r.getStatus().trim().toUpperCase());
        if (r.getHardRule() == null) {
            r.setHardRule(old != null && Boolean.TRUE.equals(old.getHardRule()));
        }
        if (r.getRealtime() == null) {
            r.setRealtime(old != null && Boolean.TRUE.equals(old.getRealtime()));
        }
        if (r.getScore() == null) {
            r.setScore(old == null || old.getScore() == null ? 0 : old.getScore());
        }
        if (r.getMinCount() == null) {
            r.setMinCount(1);
        }
        r.setVersion(old == null || old.getVersion() == null ? 1 : old.getVersion() + 1);
        r.setOperator(operator);
        if (old != null && old.getCreatedAt() != null) {
            r.setCreatedAt(old.getCreatedAt());
        } else if (r.getCreatedAt() == null) {
            r.setCreatedAt(now);
        }
        r.setUpdatedAt(now);
        if (r.getConditionExpr() != null && !r.getConditionExpr().isBlank()) {
            RiskRuleEngine.Compile c = engine.compile(r.getConditionExpr());
            r.setCompileStatus(c.ok() ? "OK" : "COMPILE_FAILED");
            r.setCompileError(c.error());
            if (!c.ok()) {
                riskStore.alert("AML_RULE_COMPILE_FAIL", r.getId(), c.error(), null);
                log.warn("AML 规则编译失败（该规则跳过并告警，不阻断交易）ruleId={} err={}", r.getId(), c.error());
            }
        } else {
            r.setCompileStatus("BUILTIN");
            r.setCompileError(null);
        }
    }

    public List<AmlRuleDTO> rules() {
        applyConfigIfChanged();
        return store.listRules();
    }

    private List<AmlRuleDTO> enabledRules() {
        return rules().stream().filter(r -> "ENABLED".equals(r.getStatus())).toList();
    }

    public AmlRuleDTO saveRule(AmlRuleDTO r, String operator) {
        if (r == null || r.getId() == null || r.getId().isBlank() || r.getRuleType() == null) {
            throw new IllegalArgumentException("id/ruleType 必填");
        }
        normalize(r, operator);
        store.saveRule(r);
        return store.getRule(r.getId());
    }

    public AmlRuleDTO disableRule(String ruleId, String operator) {
        AmlRuleDTO r = store.getRule(ruleId);
        if (r == null) {
            throw new IllegalArgumentException("AML 规则不存在: " + ruleId);
        }
        r.setStatus("DISABLED");
        r.setVersion((r.getVersion() == null ? 1 : r.getVersion()) + 1);
        r.setOperator(operator);
        r.setUpdatedAt(System.currentTimeMillis());
        store.saveRule(r);
        return store.getRule(ruleId);
    }

    // ---------------- 受理前硬规则（默认关闭，零开销） ----------------

    /**
     * 硬规则前置校验：仅当 {@code risk.aml.hard-block-enabled=true} 才执行；
     * 命中硬规则返回 {@link HardHit}，由既有 pipeline 的 {@link DispositionService} 统一分级。
     * 任何异常一律返回 null（放行），绝不因 AML 故障拦截正常交易。
     */
    public HardHit preCheck(RiskContext ctx) {
        if (!enabled() || !hardBlockEnabled()) {
            return null;
        }
        try {
            String date = bizDate(ctx.getNow());
            List<Map<String, Object>> txns = store.listTxns(ctx.getPayerId(), date, 2000);
            txns.add(currentTxn(ctx));
            for (AmlRuleDTO r : enabledRules()) {
                if (!Boolean.TRUE.equals(r.getHardRule())) {
                    continue;
                }
                AmlRuleEngine.Eval e = engine.evaluate(r, ctx, aggregate(txns, r, ctx.getNow()));
                if (e.degraded()) {
                    degraded(e, r, ctx);
                    continue;
                }
                if (e.hit()) {
                    return new HardHit(r.getId(), RiskAction.REJECT, r.getScore() == null ? 0 : r.getScore(),
                            "AML 硬规则命中：" + r.getName() + "（" + e.detail() + "）");
                }
            }
        } catch (Exception e) {
            log.warn("AML 硬规则前置校验异常（放行，不阻断交易）: {}", e.getMessage());
            riskStore.alert("AML_PRECHECK_FAIL", null, e.getMessage(), ctx.getTraceId());
        }
        return null;
    }

    // ---------------- 旁路观测（报告生成，不阻断） ----------------

    /** 记录交易流水 + 名单筛查 + 实时单笔类规则命中 → 生成 STR。任何异常只 warn。 */
    public void observe(RiskContext ctx, RiskResultDTO result) {
        if (!enabled() || ctx.getPayerId().isBlank()) {
            return;
        }
        // 只监测「已受理」的交易（pass=true）：被风控拒绝的尝试不入流水，报告基于真实受理流水
        if (result != null && !Boolean.TRUE.equals(result.getPass())) {
            return;
        }
        try {
            String date = bizDate(ctx.getNow());
            SandboxIpDataSource.Entry ip = ipProfile.resolve(ctx.getIp());
            String dir = direction(ctx.getScene());
            String deviceKey = ctx.getDeviceId().isBlank() ? null : deviceProfile.storageKey(ctx.getDeviceId());
            long txnId = store.nextId("seq_aml_txn");
            store.insertTxn(txnId, ctx.getPayerId(), ctx.getMerchantId(), ctx.getBizNo(), ctx.getAmount(), dir,
                    ctx.getScene(), ctx.getMethod(), ctx.getIp().isBlank() ? null : ctx.getIp(), ip.region(),
                    ip.country(), deviceKey, ctx.getHour(), date, ctx.getTraceId(), ctx.getNow());

            // 名单筛查（制裁/PEP，沙箱名单）：命中与否都留痕
            if (screeningEnabled()) {
                screenAndReport("PAYER", ctx.getPayerId(), ctx, date);
                if (!ctx.getMerchantId().isBlank()) {
                    screenAndReport("MERCHANT", ctx.getMerchantId(), ctx, date);
                }
            }

            // 实时单笔类规则（SANCTION/PEP 已由筛查处理，此处跳过避免重复）
            Map<String, Object> single = currentTxn(ctx);
            List<Map<String, Object>> one = new ArrayList<>();
            one.add(single);
            for (AmlRuleDTO r : enabledRules()) {
                if (!Boolean.TRUE.equals(r.getRealtime()) || isListRule(r)) {
                    continue;
                }
                AmlRuleEngine.Eval e = engine.evaluate(r, ctx, aggregate(one, r, ctx.getNow()));
                if (e.degraded()) {
                    degraded(e, r, ctx);
                    continue;
                }
                if (e.hit()) {
                    createReport(r, ctx, aggregate(one, r, ctx.getNow()), e.detail(), date, one, "REALTIME");
                }
            }
        } catch (Exception e) {
            log.warn("AML 观测异常（不阻断交易）payer={}: {}", ctx.getPayerId(), e.getMessage());
            riskStore.alert("AML_OBSERVE_FAIL", null, e.getMessage(), ctx.getTraceId());
        }
    }

    private static boolean isListRule(AmlRuleDTO r) {
        String t = r.getRuleType() == null ? "" : r.getRuleType().toUpperCase();
        return t.equals("SANCTION") || t.equals("PEP");
    }

    private void screenAndReport(String subjectType, String subject, RiskContext ctx, String date) {
        AmlWatchlistService.Hit hit = watchlist.screen(null, subject);
        Long reportId = null;
        if (hit.hit()) {
            AmlRuleDTO r = enabledRules().stream()
                    .filter(x -> hit.listType().equalsIgnoreCase(x.getRuleType()))
                    .findFirst().orElse(null);
            if (r != null) {
                RiskContext sc = subjectContext(ctx, subject);
                AmlReportDTO report = createReport(r, sc, aggregate(List.of(currentTxn(ctx)), r, ctx.getNow()),
                        subjectType + " 命中 " + hit.listType() + " 沙箱名单（" + hit.matchType() + "）",
                        date, List.of(currentTxn(ctx)), "SCREENING");
                reportId = report == null ? null : report.getId();
            }
        }
        watchlist.record(subjectType, subject, hit.hit() ? hit.listType() : null, hit, reportId, ctx.getTraceId());
    }

    private RiskContext subjectContext(RiskContext ctx, String subject) {
        RiskContext c = new RiskContext();
        c.setPayerId(subject);
        c.setMerchantId(ctx.getMerchantId());
        c.setAmount(ctx.getAmount());
        c.setScene(ctx.getScene());
        c.setMethod(ctx.getMethod());
        c.setNow(ctx.getNow());
        c.setHour(ctx.getHour());
        c.setTraceId(ctx.getTraceId());
        c.setBizNo(ctx.getBizNo());
        return c;
    }

    private Map<String, Object> currentTxn(RiskContext ctx) {
        SandboxIpDataSource.Entry ip = ipProfile.resolve(ctx.getIp());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payerId", ctx.getPayerId());
        m.put("paymentId", ctx.getBizNo());
        m.put("amount", ctx.getAmount());
        m.put("direction", direction(ctx.getScene()));
        m.put("country", ip.country());
        m.put("region", ip.region());
        m.put("hourOfDay", ctx.getHour());
        m.put("createdAt", ctx.getNow());
        return m;
    }

    private void degraded(AmlRuleEngine.Eval e, AmlRuleDTO r, RiskContext ctx) {
        degradedCount.incrementAndGet();
        MetricsSupport.count("risk.aml.degraded", "kind", e.errorKind() == null ? "UNKNOWN" : e.errorKind());
        riskStore.alert("AML_RULE_" + e.errorKind(), r.getId(), e.error(), ctx == null ? null : ctx.getTraceId());
        log.warn("[告警] AML 规则护栏降级（跳过，不阻断）ruleId={} kind={} err={}",
                r.getId(), e.errorKind(), e.error());
    }

    // ---------------- 聚合 ----------------

    private AmlRuleEngine.Agg aggregate(List<Map<String, Object>> txns, AmlRuleDTO rule, long now) {
        long threshold = rule.getThreshold() == null ? largeAmount() : rule.getThreshold();
        int ratio = rule.getRatio() == null ? structuringRatio() : rule.getRatio();
        long window = rule.getWindowMillis() == null || rule.getWindowMillis() <= 0
                ? fastWindow() : rule.getWindowMillis();
        Set<Integer> night = nightHours();
        long dayAmount = 0;
        long maxSingle = 0;
        long in = 0;
        long out = 0;
        long structuring = 0;
        long nightAmount = 0;
        long nightCount = 0;
        long crossAmount = 0;
        long crossCount = 0;
        long fastIn = 0;
        long fastOut = 0;
        for (Map<String, Object> t : txns) {
            long amount = num(t.get("amount"));
            String dir = String.valueOf(t.get("direction"));
            String country = t.get("country") == null ? "" : String.valueOf(t.get("country"));
            int hour = (int) num(t.get("hourOfDay"));
            long at = num(t.get("createdAt"));
            dayAmount += amount;
            maxSingle = Math.max(maxSingle, amount);
            if ("IN".equals(dir)) {
                in += amount;
            } else {
                out += amount;
            }
            if (amount >= threshold * ratio / 100 && amount < threshold) {
                structuring++;
            }
            if (night.contains(hour)) {
                nightAmount += amount;
                nightCount++;
            }
            // 未知归属地（空串）不算跨境，避免误报
            if (!country.isBlank() && !"CN".equalsIgnoreCase(country)) {
                crossAmount += amount;
                crossCount++;
            }
            if (at >= now - window) {
                if ("IN".equals(dir)) {
                    fastIn += amount;
                } else {
                    fastOut += amount;
                }
            }
        }
        return new AmlRuleEngine.Agg(dayAmount, txns.size(), maxSingle, in, out, structuring, nightAmount,
                nightCount, crossAmount, crossCount, fastIn, fastOut, txns);
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------------- 监测扫描（与 AmlMonitorJob 同一代码路径，幂等） ----------------

    public AmlScanResultDTO scan(String bizDate, String payerId) {
        long start = System.currentTimeMillis();
        String date = bizDate == null || bizDate.isBlank() ? bizDate(start) : bizDate.trim();
        AmlScanResultDTO out = new AmlScanResultDTO();
        out.setBizDate(date);
        List<AmlRuleDTO> rules = enabledRules();
        out.setRules(rules.size());
        List<String> payers = payerId == null || payerId.isBlank()
                ? store.distinctPayers(date) : List.of(payerId.trim());
        out.setPayers(payers.size());
        int txns = 0;
        int hits = 0;
        int created = 0;
        int reused = 0;
        int degraded = 0;
        for (String payer : payers) {
            List<Map<String, Object>> rows = store.listTxns(payer, date, 2000);
            if (rows.isEmpty()) {
                continue;
            }
            txns += rows.size();
            RiskContext ctx = new RiskContext();
            ctx.setPayerId(payer);
            ctx.setNow(start);
            ctx.setScene("PAY");
            ctx.setAmount(rows.stream().mapToLong(r -> num(r.get("amount"))).sum());
            ctx.setMerchantId(String.valueOf(rows.get(rows.size() - 1).get("merchantId")));
            ctx.setAmlLookup(lookup());
            for (AmlRuleDTO r : rules) {
                AmlRuleEngine.Agg agg = aggregate(rows, r, start);
                AmlRuleEngine.Eval e = engine.evaluate(r, ctx, agg);
                if (e.degraded()) {
                    degraded++;
                    degraded(e, r, ctx);
                    continue;
                }
                if (!e.hit()) {
                    continue;
                }
                hits++;
                out.getHitRules().add(r.getId() + ":" + r.getRuleType());
                boolean[] isNew = new boolean[1];
                AmlReportDTO report = createReport(r, ctx, agg, e.detail(), date, rows, "SCAN", isNew);
                if (report != null) {
                    out.getReportIds().add(report.getId());
                    if (isNew[0]) {
                        created++;
                    } else {
                        reused++;
                    }
                }
            }
        }
        out.setTxns(txns);
        out.setHits(hits);
        out.setCreated(created);
        out.setReused(reused);
        out.setDegraded(degraded);
        out.setElapsedMillis(System.currentTimeMillis() - start);
        log.info("R-6 AML 监测扫描 bizDate={} payers={} txns={} rules={} hits={} created={} reused={} degraded={}",
                date, out.getPayers(), txns, rules.size(), hits, created, reused, degraded);
        return out;
    }

    // ---------------- 报告生成（幂等 + 既有分级 + 差错建单） ----------------

    private AmlReportDTO createReport(AmlRuleDTO rule, RiskContext ctx, AmlRuleEngine.Agg agg, String detail,
                                      String date, List<Map<String, Object>> txns, String trigger) {
        return createReport(rule, ctx, agg, detail, date, txns, trigger, new boolean[1]);
    }

    private AmlReportDTO createReport(AmlRuleDTO rule, RiskContext ctx, AmlRuleEngine.Agg agg, String detail,
                                      String date, List<Map<String, Object>> txns, String trigger,
                                      boolean[] isNew) {
        isNew[0] = false;
        AmlReportDTO existing = store.findReport(ctx.getPayerId(), rule.getId(), date);
        if (existing != null) {
            reportReused.incrementAndGet();
            return existing;
        }
        long id = store.nextId("seq_aml_report");
        int score = rule.getScore() == null ? 0 : rule.getScore();
        long amount = agg.dayAmount() > 0 ? agg.dayAmount() : ctx.getAmount();
        // 接入既有 R-4 分级（不另起体系）：报告分档由 DispositionService 统一给出
        DispositionService.Disposition d = dispositionService.decide(ctx.getScene(), score, amount, RiskAction.PASS);
        AmlReportDTO r = new AmlReportDTO();
        r.setId(id);
        r.setReportNo("STR" + id);
        r.setPayerId(ctx.getPayerId());
        r.setMerchantId(ctx.getMerchantId());
        r.setRuleId(rule.getId());
        r.setRuleType(rule.getRuleType());
        r.setTxnAmount(amount);
        r.setTxnCount((int) agg.dayCount());
        r.setBizDate(date);
        r.setStatus("PENDING");
        r.setScore(score);
        r.setDisposition(d.action());
        r.setHardRule(Boolean.TRUE.equals(rule.getHardRule()));
        r.setRetentionUntil(ctx.getNow() + retentionDays() * 86_400_000L);
        r.setDetail(detailJson(rule, agg, detail, trigger, d.action()));
        if (!store.insertReport(r)) {
            reportReused.incrementAndGet();
            return store.findReport(ctx.getPayerId(), rule.getId(), date);
        }
        isNew[0] = true;
        reportCreated.incrementAndGet();
        MetricsSupport.count("risk.aml.report", "type", rule.getRuleType(), "disposition", d.action());
        store.insertAudit(id, "CREATE", null, "PENDING", "system", trigger + " 命中 " + rule.getId());
        for (Map<String, Object> t : txns) {
            String pid = t.get("paymentId") == null ? null : String.valueOf(t.get("paymentId"));
            if (pid == null || pid.isBlank() || "null".equals(pid)) {
                pid = "TXN:" + t.getOrDefault("id", String.valueOf(num(t.get("createdAt"))));
            }
            store.insertRef(id, pid, num(t.get("amount")), String.valueOf(t.get("direction")));
        }
        // 达 human-review 档 → 既有差错平台建单（source=PAYMENT、strategy=MANUAL，不动账）
        if (disputeEnabled() && RiskAction.MANUAL_REVIEW.equals(d.action())) {
            attachDispute(r, ctx);
        }
        log.warn("[AML] 可疑报告生成 reportNo={} payer={} rule={} type={} amount={} score={} disposition={}",
                r.getReportNo(), AmlWatchlistService.mask(r.getPayerId()), rule.getId(), rule.getRuleType(),
                amount, score, d.action());
        return r;
    }

    private void attachDispute(AmlReportDTO r, RiskContext ctx) {
        try {
            if (disputeFacade == null) {
                return;
            }
            DisputeCreateRequest req = new DisputeCreateRequest();
            req.setType("AML_STR");
            req.setSource("PAYMENT");
            req.setBizNo("AML:" + r.getReportNo());
            req.setAmount(r.getTxnAmount());
            req.setStrategy("MANUAL");
            req.setReason("AML 可疑报告转人工：" + r.getReportNo() + " rule=" + r.getRuleId()
                    + " type=" + r.getRuleType() + " score=" + r.getScore());
            var res = disputeFacade.create(req);
            if (res != null && res.isSuccess() && res.getData() != null) {
                DisputeDTO dto = res.getData();
                store.attachDispute(r.getId(), dto.getId());
                store.insertAudit(r.getId(), "DISPUTE_CREATE", "PENDING", "PENDING", "system",
                        "差错单 " + dto.getId() + "（strategy=MANUAL 不动账）");
                r.setDisputeId(dto.getId());
            }
        } catch (Exception e) {
            log.warn("AML 报告转人工建单失败（报告仍留痕，可后续补建）reportNo={}: {}", r.getReportNo(), e.getMessage());
            riskStore.alert("AML_DISPUTE_CREATE_FAIL", r.getRuleId(), e.getMessage(), ctx.getTraceId());
        }
    }

    private String detailJson(AmlRuleDTO rule, AmlRuleEngine.Agg agg, String detail, String trigger,
                              String disposition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", trigger);
        m.put("ruleId", rule.getId());
        m.put("ruleType", rule.getRuleType());
        m.put("threshold", rule.getThreshold());
        m.put("minCount", rule.getMinCount());
        m.put("ratio", rule.getRatio());
        m.put("windowMillis", rule.getWindowMillis());
        m.put("conditionExpr", rule.getConditionExpr());
        m.put("disposition", disposition);
        m.put("explain", detail);
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("dayAmount", agg.dayAmount());
        obs.put("dayCount", agg.dayCount());
        obs.put("maxSingle", agg.maxSingle());
        obs.put("inAmount", agg.inAmount());
        obs.put("outAmount", agg.outAmount());
        obs.put("structuringCount", agg.structuringCount());
        obs.put("nightAmount", agg.nightAmount());
        obs.put("nightCount", agg.nightCount());
        obs.put("crossBorderAmount", agg.crossBorderAmount());
        obs.put("crossBorderCount", agg.crossBorderCount());
        obs.put("fastIn", agg.fastIn());
        obs.put("fastOut", agg.fastOut());
        m.put("observations", obs);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------- 状态机（待审 → 上报 → 归档） ----------------

    public AmlReportDTO submit(long reportId, String operator, String note) {
        AmlReportDTO r = require(reportId);
        if (!"PENDING".equals(r.getStatus())) {
            throw new IllegalStateException("报告状态非待审，不可上报: " + r.getStatus());
        }
        int n = store.casStatus(reportId, "PENDING", "SUBMITTED", operator, note, true);
        if (n == 0) {
            throw new IllegalStateException("报告状态已变更（并发），上报失败: " + reportId);
        }
        store.insertAudit(reportId, "SUBMIT", "PENDING", "SUBMITTED", operator, note);
        log.info("R-6 STR 上报 reportId={} operator={} note={}", reportId, operator, note);
        return get(reportId);
    }

    public AmlReportDTO close(long reportId, String operator, String note) {
        AmlReportDTO r = require(reportId);
        String from = r.getStatus();
        if (!"SUBMITTED".equals(from) && !"PENDING".equals(from)) {
            throw new IllegalStateException("报告已归档，不可重复归档: " + from);
        }
        int n = store.casStatus(reportId, from, "CLOSED", operator, note, false);
        if (n == 0) {
            throw new IllegalStateException("报告状态已变更（并发），归档失败: " + reportId);
        }
        store.insertAudit(reportId, "CLOSE", from, "CLOSED", operator, note);
        log.info("R-6 STR 归档 reportId={} from={} operator={}", reportId, from, operator);
        return get(reportId);
    }

    private AmlReportDTO require(long reportId) {
        AmlReportDTO r = store.getReport(reportId);
        if (r == null) {
            throw new IllegalArgumentException("可疑报告不存在: " + reportId);
        }
        return r;
    }

    public AmlReportDTO get(long reportId) {
        AmlReportDTO r = require(reportId);
        r.setPaymentIds(store.listRefs(reportId));
        return r;
    }

    public List<AmlReportDTO> list(String status, String bizDate, String payerId, int limit) {
        return store.listReports(status, bizDate, payerId, limit);
    }

    public List<Map<String, Object>> audit(long reportId) {
        return store.listAudit(reportId);
    }

    public List<Map<String, Object>> txns(String payerId, String bizDate, int limit) {
        return store.listTxns(payerId, bizDate, limit);
    }

    public List<AmlScreeningDTO> screenings(String subject, int limit) {
        return store.listScreenings(subject == null || subject.isBlank()
                ? null : AmlWatchlistService.mask(subject), limit);
    }

    public AmlScreeningDTO screen(String subjectType, String subject) {
        AmlWatchlistService.Hit hit = watchlist.screen(null, subject);
        return watchlist.record(subjectType == null || subjectType.isBlank() ? "PAYER" : subjectType,
                subject, hit.hit() ? hit.listType() : null, hit, null, null);
    }

    // ---------------- QLExpress 函数能力（dayAmount/dayCount/sanctionHit/pepHit） ----------------

    public RiskContext.AmlLookup lookup() {
        return new RiskContext.AmlLookup() {
            @Override
            public long dayAmount(String payerId) {
                return store.daySum(payerId, bizDate(System.currentTimeMillis()))[0];
            }

            @Override
            public long dayCount(String payerId) {
                return store.daySum(payerId, bizDate(System.currentTimeMillis()))[1];
            }

            @Override
            public boolean watchlistHit(String listType, String subject) {
                return watchlist.screen(listType, subject).hit();
            }
        };
    }

    // ---------------- 运行态 ----------------

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amlEnabled", enabled());
        m.put("hardBlockEnabled", hardBlockEnabled());
        m.put("amlScoreEnabled", scoreEnabled());
        m.put("screeningEnabled", screeningEnabled());
        m.put("disputeEnabled", disputeEnabled());
        m.put("autoScanEnabled", config.getBool(RiskPlusKeys.AML_AUTO_SCAN, RiskPlusKeys.DEFAULT_AML_AUTO_SCAN));
        m.put("largeAmountThreshold", largeAmount());
        m.put("dayAmountThreshold", dayAmount());
        m.put("splitCount", splitCount());
        m.put("structuringCount", structuringCount());
        m.put("structuringRatio", structuringRatio());
        m.put("fastWindowMillis", fastWindow());
        m.put("fastRatio", fastRatio());
        m.put("nightHours", nightHours());
        m.put("nightAmountThreshold", nightAmount());
        m.put("crossBorderAmountThreshold", crossBorderAmount());
        m.put("retentionDays", retentionDays());
        m.put("ruleCount", store.ruleCount());
        m.put("reportCount", store.reportCount());
        m.put("pendingReports", store.reportCountByStatus("PENDING"));
        m.put("submittedReports", store.reportCountByStatus("SUBMITTED"));
        m.put("closedReports", store.reportCountByStatus("CLOSED"));
        m.put("txnCount", store.txnCount());
        m.put("screeningCount", store.screeningCount());
        m.put("watchlistCount", watchlist.count());
        m.put("watchlistSource", "SANDBOX");
        m.put("externalApi", false);
        m.put("reportCreated", reportCreated.get());
        m.put("reportReused", reportReused.get());
        m.put("degradedCount", degradedCount.get());
        m.put("dialect", "risk-plus");
        return m;
    }
}