package com.qoder.payment.risk.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.QuotaRuleDTO;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.facade.risk.RiskSnapshotDTO;
import com.qoder.payment.facade.risk.RiskThresholdDTO;
import com.qoder.payment.risk.engine.RiskRuleEngine;
import com.qoder.payment.risk.engine.RuleGrayRouter;
import com.qoder.payment.risk.store.RiskStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R-1~R-4 配置装载与版本治理（DynamicConfig 下发 + DB 快照权威 + 灰度/影子/回滚 + 变更审计）。
 *
 * <p>四类载荷统一版本化（{@code risk_config_snapshot}）：
 * {@code RULES}（{@code risk.rules.json}）/{@code QUOTAS}（{@code risk.quota.rules.json}）/
 * {@code LISTS}（{@code risk.list.entries.json}）/{@code THRESHOLDS}（{@code risk.score.thresholds.json}）。
 *
 * <p><b>热更</b>：Apollo 监听回调 + {@value RiskKeys#POLL_SECONDS}s 轮询比对原始值；
 * 值变化 → 解析归一 → 落新 ACTIVE 版本 → 原子换入内存缓存，<b>改规则不重启生效</b>。
 * <p><b>last-known-good</b>：JSON 解析失败 → 保留上一版 + 告警，绝不清空规则或阻断支付。
 * <p><b>回滚</b>：取上一版 payload 生成新 ACTIVE 版本（source=ROLLBACK），历史行永不删除；
 * 由于配置原始值未变，轮询不会把回滚结果覆盖回去。
 * <p><b>审计</b>：每次变更（下发/控制台/回滚）都是一行快照，含 operator/source/changeNote/createdAt。
 */
@Slf4j
@Component
public class RiskConfigManager {

    public static final String RULES = "RULES";
    public static final String QUOTAS = "QUOTAS";
    public static final String LISTS = "LISTS";
    public static final String THRESHOLDS = "THRESHOLDS";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Set<String> DIMENSIONS = Set.of("USER", "MERCHANT", "CHANNEL", "METHOD", "CARD");
    private static final Set<String> PERIODS = Set.of("SINGLE", "DAY", "MONTH", "TOTAL");
    private static final Set<String> LIST_TYPES = Set.of("BLACK", "WHITE", "GRAY");
    private static final Set<String> VALUE_TYPES = Set.of("USER", "MERCHANT", "CARD", "DEVICE", "IP");

    private final DynamicConfig config;
    private final RiskStore store;
    private final RiskRuleEngine engine;

    private volatile List<RiskRuleDTO> rules = List.of();
    private volatile List<QuotaRuleDTO> quotas = List.of();
    private volatile List<ListEntryDTO> lists = List.of();
    private volatile Map<String, RiskThresholdDTO> thresholds = Map.of();
    private volatile boolean globalShadow = RiskKeys.DEFAULT_GLOBAL_SHADOW;
    private final Map<String, Integer> activeVersions = new ConcurrentHashMap<>();
    /** 配置原始值缓存；ConcurrentHashMap 不允许 null value，用哨兵表示「配置源无此键」。 */
    private static final String ABSENT = "\u0000ABSENT";
    private final Map<String, String> lastRaw = new ConcurrentHashMap<>();

    private static String rawKey(String raw) {
        return raw == null ? ABSENT : raw;
    }
    private final AtomicLong configReloads = new AtomicLong();
    private final AtomicLong configParseFailures = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "risk-config-poll");
        t.setDaemon(true);
        return t;
    });

    public RiskConfigManager(DynamicConfig config, RiskStore store, RiskRuleEngine engine) {
        this.config = config;
        this.store = store;
        this.engine = engine;
    }

    @PostConstruct
    public void init() {
        for (String kind : new String[]{RULES, QUOTAS, LISTS, THRESHOLDS}) {
            String key = keyOf(kind);
            String raw = config.get(key, null);
            RiskSnapshotDTO active = null;
            try {
                active = store.activeSnapshot(kind);
            } catch (Exception e) {
                log.warn("风控配置快照读取失败 kind={}（用配置/默认值兜底）: {}", kind, e.getMessage());
            }
            if (active != null) {
                swap(kind, active.getPayload());
                activeVersions.put(kind, active.getVersion());
                log.info("风控配置载入 kind={} version={} source={}", kind, active.getVersion(), active.getSource());
            } else {
                String payload = canonical(kind, raw);
                RiskSnapshotDTO snap = safeNewVersion(kind, payload, raw == null ? "BOOT" : "CONFIG", "system", "初始化");
                swap(kind, payload);
                activeVersions.put(kind, snap == null ? 1 : snap.getVersion());
            }
            lastRaw.put(kind, rawKey(raw));
            config.addListener(key, (k, oldV, newV) -> {
                log.info("风控配置变更推送 kind={} key={}", kind, k);
                applyIfChanged(kind);
            });
        }
        scheduler.scheduleWithFixedDelay(this::pollQuietly, RiskKeys.POLL_SECONDS, RiskKeys.POLL_SECONDS,
                TimeUnit.SECONDS);
        log.info("RiskConfigManager 就绪 provider={} rules={} quotas={} lists={} globalShadow={} poll={}s",
                config.provider(), rules.size(), quotas.size(), lists.size(), globalShadow, RiskKeys.POLL_SECONDS);
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    public static String keyOf(String kind) {
        return switch (kind) {
            case QUOTAS -> RiskKeys.QUOTAS_JSON;
            case LISTS -> RiskKeys.LISTS_JSON;
            case THRESHOLDS -> RiskKeys.THRESHOLDS_JSON;
            default -> RiskKeys.RULES_JSON;
        };
    }

    private void pollQuietly() {
        try {
            for (String kind : new String[]{RULES, QUOTAS, LISTS, THRESHOLDS}) {
                applyIfChanged(kind);
            }
        } catch (Exception e) {
            log.warn("风控配置轮询异常（保留 last-known-good）: {}", e.getMessage());
        }
    }

    /** 原始值变化才换版（回滚后配置未变 → 不覆盖回滚结果）。 */
    private synchronized void applyIfChanged(String kind) {
        String raw = config.get(keyOf(kind), null);
        if (Objects.equals(rawKey(raw), lastRaw.get(kind))) {
            return;
        }
        lastRaw.put(kind, rawKey(raw));
        String payload = canonical(kind, raw);
        if (payload == null) {
            configParseFailures.incrementAndGet();
            MetricsSupport.count("risk.config.parse.fail", "kind", kind);
            store.alert("CONFIG_PARSE_FAIL", kind, "配置 JSON 解析失败，保留 last-known-good", null);
            log.warn("风控配置解析失败 kind={}，保留上一版（last-known-good）", kind);
            return;
        }
        RiskSnapshotDTO snap = safeNewVersion(kind, payload, "CONFIG", "config-center", "DynamicConfig 下发");
        swap(kind, payload);
        if (snap != null) {
            activeVersions.put(kind, snap.getVersion());
        }
        configReloads.incrementAndGet();
        MetricsSupport.count("risk.config.reload", "kind", kind);
        log.info("风控配置热更生效 kind={} version={}", kind, snap == null ? "?" : snap.getVersion());
    }

    private RiskSnapshotDTO safeNewVersion(String kind, String payload, String source, String operator, String note) {
        try {
            return store.newVersion(kind, payload, source, operator, note);
        } catch (Exception e) {
            log.warn("风控配置版本落库失败（内存仍生效）kind={}: {}", kind, e.getMessage());
            return null;
        }
    }

    // ---------------- 解析 / 归一 ----------------

    /** 解析 + 归一 + 编译校验；返回规范 payload；JSON 不可解析返回 null（触发 last-known-good）。 */
    private String canonical(String kind, String raw) {
        try {
            return switch (kind) {
                case QUOTAS -> MAPPER.writeValueAsString(normalizeQuotas(parseArray(raw, QuotaRuleDTO.class)));
                case LISTS -> MAPPER.writeValueAsString(normalizeLists(parseArray(raw, ListEntryDTO.class)));
                case THRESHOLDS -> MAPPER.writeValueAsString(normalizeThresholds(raw));
                default -> MAPPER.writeValueAsString(normalizeRules(raw));
            };
        } catch (Exception e) {
            log.warn("风控配置归一失败 kind={}: {}", kind, e.getMessage());
            return null;
        }
    }

    private <T> List<T> parseArray(String raw, Class<T> type) throws Exception {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        String t = raw.trim();
        if (t.startsWith("{")) {
            // 对象形式：{"rules":[...]} / {"quotas":[...]} / {"entries":[...]}
            var node = MAPPER.readTree(t);
            var arr = node.has("rules") ? node.get("rules")
                    : node.has("quotas") ? node.get("quotas")
                    : node.has("entries") ? node.get("entries") : null;
            if (arr == null || !arr.isArray()) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(arr.toString(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        }
        return MAPPER.readValue(t, MAPPER.getTypeFactory().constructCollectionType(List.class, type));
    }

    /** RULES 载荷规范形式：{"globalShadow":bool,"rules":[...]}。 */
    private Map<String, Object> normalizeRules(String raw) throws Exception {
        boolean shadow = config.getBool(RiskKeys.GLOBAL_SHADOW, RiskKeys.DEFAULT_GLOBAL_SHADOW);
        List<RiskRuleDTO> parsed = parseArray(raw, RiskRuleDTO.class);
        if (raw != null && raw.trim().startsWith("{")) {
            var node = MAPPER.readTree(raw.trim());
            if (node.has("globalShadow")) {
                shadow = node.get("globalShadow").asBoolean(shadow);
            }
        }
        long now = System.currentTimeMillis();
        int idx = 0;
        List<RiskRuleDTO> out = new ArrayList<>();
        for (RiskRuleDTO r : parsed) {
            idx++;
            if (r.getId() == null || r.getId().isBlank()) {
                r.setId("R" + idx);
            }
            if (r.getName() == null || r.getName().isBlank()) {
                r.setName(r.getId());
            }
            if (r.getScene() == null || r.getScene().isBlank()) {
                r.setScene("ALL");
            }
            if (r.getPriority() == null) {
                r.setPriority(100);
            }
            if (r.getAction() == null || r.getAction().isBlank()) {
                r.setAction("REJECT");
            }
            r.setAction(r.getAction().trim().toUpperCase());
            if (r.getScore() == null) {
                r.setScore(0);
            }
            if (r.getGrayPercent() == null) {
                r.setGrayPercent(100);
            }
            r.setGrayPercent(Math.max(0, Math.min(100, r.getGrayPercent())));
            if (r.getShadow() == null) {
                r.setShadow(false);
            }
            if (r.getStatus() == null || r.getStatus().isBlank()) {
                r.setStatus("PUBLISHED");
            }
            r.setStatus(r.getStatus().trim().toUpperCase());
            if (r.getVersion() == null) {
                r.setVersion(1);
            }
            if (r.getCreatedAt() == null) {
                r.setCreatedAt(now);
            }
            r.setUpdatedAt(now);
            RiskRuleEngine.Compile c = engine.compile(r.getConditionExpr());
            r.setCompileStatus(c.ok() ? "OK" : "COMPILE_FAILED");
            r.setCompileError(c.error());
            if (!c.ok()) {
                // §5.4：编译失败 → 该规则跳过（放行）+ 告警，绝不阻断正常交易
                store.alert("RULE_COMPILE_FAIL", r.getId(), c.error(), null);
                log.warn("规则编译失败（该规则放行并告警）ruleId={} expr={} err={}",
                        r.getId(), r.getConditionExpr(), c.error());
            }
            out.add(r);
        }
        RuleGrayRouter.sort(out);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("globalShadow", shadow);
        payload.put("rules", out);
        return payload;
    }

    private List<QuotaRuleDTO> normalizeQuotas(List<QuotaRuleDTO> parsed) {
        long now = System.currentTimeMillis();
        int idx = 0;
        List<QuotaRuleDTO> out = new ArrayList<>();
        for (QuotaRuleDTO q : parsed) {
            idx++;
            String dims = q.getDimensions() == null ? "USER" : q.getDimensions().trim().toUpperCase();
            boolean valid = true;
            for (String d : dims.split(",")) {
                if (!DIMENSIONS.contains(d.trim())) {
                    valid = false;
                }
            }
            String period = q.getPeriod() == null ? "DAY" : q.getPeriod().trim().toUpperCase();
            if (!PERIODS.contains(period)) {
                valid = false;
            }
            if (!valid) {
                store.alert("QUOTA_INVALID", q.getId(), "维度/周期非法: " + dims + "/" + period, null);
                log.warn("限额规则非法（跳过并告警）id={} dimensions={} period={}", q.getId(), dims, period);
                continue;
            }
            if (q.getId() == null || q.getId().isBlank()) {
                q.setId("Q" + idx);
            }
            if (q.getName() == null || q.getName().isBlank()) {
                q.setName(q.getId());
            }
            q.setDimensions(dims);
            q.setPeriod(period);
            if (q.getScene() == null || q.getScene().isBlank()) {
                q.setScene("ALL");
            }
            if (q.getStatus() == null || q.getStatus().isBlank()) {
                q.setStatus("ENABLED");
            }
            q.setStatus(q.getStatus().trim().toUpperCase());
            if (q.getVersion() == null) {
                q.setVersion(1);
            }
            if (q.getCreatedAt() == null) {
                q.setCreatedAt(now);
            }
            q.setUpdatedAt(now);
            out.add(q);
        }
        return out;
    }

    private List<ListEntryDTO> normalizeLists(List<ListEntryDTO> parsed) {
        long now = System.currentTimeMillis();
        int idx = 0;
        List<ListEntryDTO> out = new ArrayList<>();
        for (ListEntryDTO e : parsed) {
            idx++;
            String lt = e.getListType() == null ? "" : e.getListType().trim().toUpperCase();
            String vt = e.getValueType() == null ? "" : e.getValueType().trim().toUpperCase();
            if (!LIST_TYPES.contains(lt) || !VALUE_TYPES.contains(vt)
                    || e.getValue() == null || e.getValue().isBlank()) {
                store.alert("LIST_INVALID", e.getId(), "名单类型/值类型/值非法: " + lt + "/" + vt, null);
                log.warn("名单条目非法（跳过并告警）id={} listType={} valueType={}", e.getId(), lt, vt);
                continue;
            }
            if (e.getId() == null || e.getId().isBlank()) {
                e.setId("L" + idx);
            }
            e.setListType(lt);
            e.setValueType(vt);
            e.setValue(e.getValue().trim());
            if (e.getAction() == null || e.getAction().isBlank()) {
                e.setAction(switch (lt) {
                    case "WHITE" -> "PASS";
                    case "GRAY" -> "CHALLENGE";
                    default -> "REJECT";
                });
            }
            e.setAction(e.getAction().trim().toUpperCase());
            if (e.getStatus() == null || e.getStatus().isBlank()) {
                e.setStatus("ENABLED");
            }
            e.setStatus(e.getStatus().trim().toUpperCase());
            if (e.getCreatedAt() == null) {
                e.setCreatedAt(now);
            }
            e.setUpdatedAt(now);
            out.add(e);
        }
        return out;
    }

    private Map<String, RiskThresholdDTO> normalizeThresholds(String raw) throws Exception {
        Map<String, RiskThresholdDTO> out = new LinkedHashMap<>();
        out.put("PAY", defaultThresholds("PAY"));
        if (raw == null || raw.isBlank()) {
            return out;
        }
        var node = MAPPER.readTree(raw.trim());
        if (node.has("scene")) {
            RiskThresholdDTO t = MAPPER.readValue(node.toString(), RiskThresholdDTO.class);
            out.put(sceneKey(t.getScene()), fillThresholds(t));
        } else {
            node.fields().forEachRemaining(en -> {
                try {
                    RiskThresholdDTO t = MAPPER.readValue(en.getValue().toString(), RiskThresholdDTO.class);
                    t.setScene(en.getKey());
                    out.put(sceneKey(en.getKey()), fillThresholds(t));
                } catch (Exception ex) {
                    store.alert("THRESHOLD_INVALID", en.getKey(), ex.getMessage(), null);
                }
            });
        }
        return out;
    }

    private static String sceneKey(String scene) {
        return scene == null || scene.isBlank() ? "PAY" : scene.trim().toUpperCase();
    }

    public static RiskThresholdDTO defaultThresholds(String scene) {
        RiskThresholdDTO t = new RiskThresholdDTO();
        t.setScene(sceneKey(scene));
        t.setChallengeScore(RiskKeys.DEFAULT_CHALLENGE_SCORE);
        t.setLimitScore(RiskKeys.DEFAULT_LIMIT_SCORE);
        t.setRejectScore(RiskKeys.DEFAULT_REJECT_SCORE);
        t.setReviewScore(RiskKeys.DEFAULT_REVIEW_SCORE);
        t.setLimitAmount(RiskKeys.DEFAULT_LIMIT_AMOUNT);
        t.setVersion(1);
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }

    private static RiskThresholdDTO fillThresholds(RiskThresholdDTO t) {
        RiskThresholdDTO d = defaultThresholds(t.getScene());
        if (t.getChallengeScore() != null) {
            d.setChallengeScore(t.getChallengeScore());
        }
        if (t.getLimitScore() != null) {
            d.setLimitScore(t.getLimitScore());
        }
        if (t.getRejectScore() != null) {
            d.setRejectScore(t.getRejectScore());
        }
        if (t.getReviewScore() != null) {
            d.setReviewScore(t.getReviewScore());
        }
        if (t.getLimitAmount() != null) {
            d.setLimitAmount(t.getLimitAmount());
        }
        d.setVersion(t.getVersion() == null ? 1 : t.getVersion());
        d.setUpdatedAt(System.currentTimeMillis());
        return d;
    }

    // ---------------- 换入内存 ----------------

    private void swap(String kind, String payload) {
        try {
            switch (kind) {
                case QUOTAS -> {
                    List<QuotaRuleDTO> list = MAPPER.readValue(payload,
                            MAPPER.getTypeFactory().constructCollectionType(List.class, QuotaRuleDTO.class));
                    quotas = List.copyOf(list);
                }
                case LISTS -> {
                    List<ListEntryDTO> list = MAPPER.readValue(payload,
                            MAPPER.getTypeFactory().constructCollectionType(List.class, ListEntryDTO.class));
                    lists = List.copyOf(list);
                }
                case THRESHOLDS -> {
                    Map<String, RiskThresholdDTO> map = MAPPER.readValue(payload,
                            MAPPER.getTypeFactory().constructMapType(Map.class, String.class, RiskThresholdDTO.class));
                    thresholds = Map.copyOf(map);
                }
                default -> {
                    var node = MAPPER.readTree(payload);
                    globalShadow = node.path("globalShadow").asBoolean(RiskKeys.DEFAULT_GLOBAL_SHADOW);
                    List<RiskRuleDTO> list = MAPPER.readValue(node.path("rules").toString(),
                            MAPPER.getTypeFactory().constructCollectionType(List.class, RiskRuleDTO.class));
                    rules = List.copyOf(list);
                }
            }
        } catch (Exception e) {
            log.warn("风控配置换入失败 kind={}（保留 last-known-good）: {}", kind, e.getMessage());
        }
    }

    /** 控制台变更统一入口：归一 → 落新版本 → 换入内存。 */
    private synchronized RiskSnapshotDTO commit(String kind, Object typedPayload, String source,
                                                String operator, String note) {
        String payload;
        try {
            String raw = MAPPER.writeValueAsString(typedPayload);
            payload = canonical(kind, raw);
            if (payload == null) {
                throw new IllegalArgumentException("载荷归一失败");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("载荷非法: " + e.getMessage(), e);
        }
        RiskSnapshotDTO snap = store.newVersion(kind, payload, source, operator, note);
        swap(kind, payload);
        activeVersions.put(kind, snap.getVersion());
        // 控制台变更后配置原始值不变 → 轮询不会覆盖；此处同步 lastRaw 保持语义清晰
        lastRaw.put(kind, rawKey(config.get(keyOf(kind), null)));
        MetricsSupport.count("risk.config.commit", "kind", kind, "source", source);
        log.info("风控配置变更 kind={} version={} source={} operator={} note={}",
                kind, snap.getVersion(), source, operator, note);
        return snap;
    }

    /** 一键回滚到上一版。 */
    public synchronized RiskSnapshotDTO rollback(String kind, String operator) {
        RiskSnapshotDTO active = store.activeSnapshot(kind);
        int activeVersion = active == null ? activeVersions.getOrDefault(kind, 1) : active.getVersion();
        RiskSnapshotDTO prev = store.previousVersion(kind, activeVersion);
        if (prev == null) {
            throw new IllegalStateException("无可回滚的上一版 kind=" + kind);
        }
        RiskSnapshotDTO snap;
        try {
            snap = commit(kind, MAPPER.readValue(prev.getPayload(), Object.class), "ROLLBACK",
                    operator, "回滚至 v" + prev.getVersion());
        } catch (Exception e) {
            throw new IllegalStateException("回滚载荷解析失败 kind=" + kind + ": " + e.getMessage(), e);
        }
        return snap;
    }

    // ---------------- 只读访问 ----------------

    public List<RiskRuleDTO> rules() {
        return rules;
    }

    public List<QuotaRuleDTO> quotas() {
        return quotas;
    }

    public List<ListEntryDTO> lists() {
        return lists;
    }

    public RiskThresholdDTO thresholds(String scene) {
        RiskThresholdDTO t = thresholds.get(sceneKey(scene));
        return t == null ? defaultThresholds(scene) : t;
    }

    public Map<String, RiskThresholdDTO> allThresholds() {
        return thresholds;
    }

    /** 全局影子：配置默认 true；控制台可显式关闭（发布前观察误杀率）。 */
    public boolean globalShadow() {
        return globalShadow;
    }

    public int version(String kind) {
        return activeVersions.getOrDefault(kind, 0);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configProvider", config.provider());
        m.put("rulesVersion", version(RULES));
        m.put("quotasVersion", version(QUOTAS));
        m.put("listsVersion", version(LISTS));
        m.put("thresholdsVersion", version(THRESHOLDS));
        m.put("ruleCount", rules.size());
        m.put("quotaCount", quotas.size());
        m.put("listCount", lists.size());
        m.put("globalShadow", globalShadow);
        m.put("engineEnabled", config.getBool(RiskKeys.ENGINE_ENABLED, RiskKeys.DEFAULT_ENGINE_ENABLED));
        m.put("quotaEnabled", config.getBool(RiskKeys.QUOTA_ENABLED, RiskKeys.DEFAULT_QUOTA_ENABLED));
        m.put("listEnabled", config.getBool(RiskKeys.LIST_ENABLED, RiskKeys.DEFAULT_LIST_ENABLED));
        m.put("scoreEnabled", config.getBool(RiskKeys.SCORE_ENABLED, RiskKeys.DEFAULT_SCORE_ENABLED));
        m.put("compileFailedRules", rules.stream().filter(r -> "COMPILE_FAILED".equals(r.getCompileStatus())).count());
        m.put("configReloads", configReloads.get());
        m.put("configParseFailures", configParseFailures.get());
        m.put("pollSeconds", RiskKeys.POLL_SECONDS);
        m.put("alertCount", store.alertCount());
        m.putAll(engine.stats());
        return m;
    }

    /** 强制拉取（local 重读文件；Apollo 重建快照）后比对换版。 */
    public Map<String, Object> reload() {
        try {
            config.refresh();
        } catch (Exception e) {
            log.warn("配置源 refresh 异常（继续比对）: {}", e.getMessage());
        }
        for (String kind : new String[]{RULES, QUOTAS, LISTS, THRESHOLDS}) {
            applyIfChanged(kind);
        }
        return status();
    }

    // ---------------- 控制台变更（R-1） ----------------

    public RiskSnapshotDTO saveRule(RiskRuleDTO rule, String operator) {
        List<RiskRuleDTO> next = new ArrayList<>(rules);
        int i = indexOfRule(next, rule.getId());
        if (rule.getStatus() == null || rule.getStatus().isBlank()) {
            rule.setStatus("DRAFT");
        }
        if (i >= 0) {
            RiskRuleDTO old = next.get(i);
            rule.setVersion((old.getVersion() == null ? 1 : old.getVersion()) + 1);
            rule.setCreatedAt(old.getCreatedAt());
            if (rule.getGrayPercent() == null) {
                rule.setGrayPercent(old.getGrayPercent());
            }
            if (rule.getWhitelist() == null) {
                rule.setWhitelist(old.getWhitelist());
            }
            if (rule.getShadow() == null) {
                rule.setShadow(old.getShadow());
            }
            next.set(i, rule);
        } else {
            rule.setVersion(1);
            next.add(rule);
        }
        return commit(RULES, rulesPayload(next), "CONSOLE", operator, "保存规则 " + rule.getId());
    }

    public RiskSnapshotDTO publishRule(String ruleId, Integer grayPercent, String whitelist, String operator) {
        List<RiskRuleDTO> next = new ArrayList<>(rules);
        int i = indexOfRule(next, ruleId);
        if (i < 0) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        RiskRuleDTO r = next.get(i);
        r.setStatus("PUBLISHED");
        if (grayPercent != null) {
            r.setGrayPercent(grayPercent);
        }
        if (whitelist != null) {
            r.setWhitelist(whitelist);
        }
        r.setVersion((r.getVersion() == null ? 1 : r.getVersion()) + 1);
        return commit(RULES, rulesPayload(next), "CONSOLE", operator,
                "发布规则 " + ruleId + " gray=" + r.getGrayPercent());
    }

    public RiskSnapshotDTO disableRule(String ruleId, String operator) {
        return mutateRule(ruleId, r -> r.setStatus("DISABLED"), operator, "停用规则 " + ruleId);
    }

    public RiskSnapshotDTO shadowRule(String ruleId, boolean on, String operator) {
        return mutateRule(ruleId, r -> r.setShadow(on), operator, "影子模式 " + ruleId + "=" + on);
    }

    private RiskSnapshotDTO mutateRule(String ruleId, java.util.function.Consumer<RiskRuleDTO> fn,
                                       String operator, String note) {
        List<RiskRuleDTO> next = new ArrayList<>(rules);
        int i = indexOfRule(next, ruleId);
        if (i < 0) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        RiskRuleDTO r = next.get(i);
        fn.accept(r);
        r.setVersion((r.getVersion() == null ? 1 : r.getVersion()) + 1);
        return commit(RULES, rulesPayload(next), "CONSOLE", operator, note);
    }

    public RiskSnapshotDTO setGlobalShadow(boolean on, String operator) {
        return commit(RULES, rulesPayload(new ArrayList<>(rules), on), "CONSOLE", operator, "全局影子模式=" + on);
    }

    private Map<String, Object> rulesPayload(List<RiskRuleDTO> list) {
        return rulesPayload(list, globalShadow);
    }

    private static Map<String, Object> rulesPayload(List<RiskRuleDTO> list, boolean shadow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("globalShadow", shadow);
        m.put("rules", list);
        return m;
    }

    private static int indexOfRule(List<RiskRuleDTO> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() != null && list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------- 控制台变更（R-2/R-3/R-4） ----------------

    public RiskSnapshotDTO saveQuota(QuotaRuleDTO quota, String operator) {
        List<QuotaRuleDTO> next = new ArrayList<>(quotas);
        int i = -1;
        for (int j = 0; j < next.size(); j++) {
            if (next.get(j).getId() != null && next.get(j).getId().equals(quota.getId())) {
                i = j;
            }
        }
        if (i >= 0) {
            quota.setVersion((next.get(i).getVersion() == null ? 1 : next.get(i).getVersion()) + 1);
            quota.setCreatedAt(next.get(i).getCreatedAt());
            next.set(i, quota);
        } else {
            quota.setVersion(1);
            next.add(quota);
        }
        return commit(QUOTAS, next, "CONSOLE", operator, "保存限额 " + quota.getId());
    }

    public RiskSnapshotDTO disableQuota(String quotaId, String operator) {
        List<QuotaRuleDTO> next = new ArrayList<>(quotas);
        for (QuotaRuleDTO q : next) {
            if (quotaId.equals(q.getId())) {
                q.setStatus("DISABLED");
                q.setVersion((q.getVersion() == null ? 1 : q.getVersion()) + 1);
                return commit(QUOTAS, next, "CONSOLE", operator, "停用限额 " + quotaId);
            }
        }
        throw new IllegalArgumentException("限额规则不存在: " + quotaId);
    }

    public RiskSnapshotDTO addListEntry(ListEntryDTO entry, String operator) {
        List<ListEntryDTO> next = new ArrayList<>(lists);
        // 同 (valueType,value) 唯一：覆盖既有行（保留 id，审计在新快照里）
        int i = -1;
        for (int j = 0; j < next.size(); j++) {
            ListEntryDTO e = next.get(j);
            if (Objects.equals(e.getValueType(), upper(entry.getValueType()))
                    && Objects.equals(e.getValue(), entry.getValue() == null ? null : entry.getValue().trim())) {
                i = j;
            }
        }
        entry.setOperator(operator);
        if (i >= 0) {
            entry.setId(next.get(i).getId());
            entry.setCreatedAt(next.get(i).getCreatedAt());
            entry.setStatus("ENABLED");
            next.set(i, entry);
        } else {
            entry.setStatus("ENABLED");
            next.add(entry);
        }
        return commit(LISTS, next, "CONSOLE", operator,
                "名单新增/更新 " + entry.getListType() + ":" + entry.getValueType() + ":" + entry.getValue());
    }

    public RiskSnapshotDTO removeListEntry(String entryId, String operator) {
        List<ListEntryDTO> next = new ArrayList<>(lists);
        for (ListEntryDTO e : next) {
            if (entryId.equals(e.getId())) {
                e.setStatus("REMOVED");
                e.setOperator(operator);
                return commit(LISTS, next, "CONSOLE", operator,
                        "名单移除 " + e.getListType() + ":" + e.getValueType() + ":" + e.getValue());
            }
        }
        throw new IllegalArgumentException("名单条目不存在: " + entryId);
    }

    public RiskSnapshotDTO setThresholds(RiskThresholdDTO t, String operator) {
        Map<String, RiskThresholdDTO> next = new LinkedHashMap<>(thresholds);
        RiskThresholdDTO old = next.get(sceneKey(t.getScene()));
        t.setVersion(old == null || old.getVersion() == null ? 1 : old.getVersion() + 1);
        next.put(sceneKey(t.getScene()), t);
        return commit(THRESHOLDS, next, "CONSOLE", operator, "阈值变更 scene=" + t.getScene());
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}