package com.qoder.payment.common.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * I-7 Sentinel 规则数据源（跨域基础设施，双 profile）。
 *
 * <p>规则来源优先级：
 * <ol>
 *   <li>{@code payment.sentinel.apollo.enabled=true} 且 classpath 含 Apollo 客户端 →
 *       Apollo namespace {@code sentinel-rules}（key {@code rules}，整份 JSON 文档）；
 *       Apollo 未部署/类缺失/读取失败 → 自动回落本地文件（I-2 落地前的降级 seam）；</li>
 *   <li>本地文件 {@code payment.sentinel.rules.file}
 *       （默认 {@code ~/.config/payment-pro/sentinel-rules.json}，仓库只留 example）。</li>
 * </ol>
 *
 * <p>热更新：守护线程每 5s 轮询（文件 mtime+size / Apollo 本地缓存），变化即重载。
 *
 * <p><b>资金安全降级红线</b>：文件缺失/损坏/解析失败 → 清空限流规则（默认放行，不阻断支付），
 * 仅保留内置保守熔断规则（下游真实故障才触发）；Sentinel 自身故障由
 * {@link SentinelGuard} fail-open 兜底。任何加载异常都不向业务链路抛出。
 *
 * <p>JSON 结构（规则字段与 Sentinel 原生 POJO 对齐，未知字段忽略）：
 * <pre>{@code
 * {
 *   "version": 1,
 *   "flowRules":      [{"resource":"/openapi/trade.create","grade":1,"count":100}],
 *   "paramFlowRules": [{"resource":"openapi:trade.create","paramIdx":0,"grade":1,"count":20,"durationInSec":1}],
 *   "degradeRules":   [{"resource":"com.qoder.payment.facade.accounting.AccountingFacade",
 *                       "grade":2,"count":5,"timeWindow":10,"minRequestAmount":5,"statIntervalMs":10000}]
 * }
 * }</pre>
 * grade：flow/param 1=QPS；degrade 0=慢调用比例 1=异常比例 2=异常数。
 */
public class SentinelRuleLoader implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleLoader.class);

    private static final long POLL_INTERVAL_SECONDS = 5;

    /** 网关消费的核心业务 facade（内置保守熔断：10s 内 ≥5 次 RPC 异常 → 熔断 10s）。 */
    private static final String[] DEFAULT_DEGRADE_RESOURCES = {
            "com.qoder.payment.facade.accounting.AccountingFacade",
            "com.qoder.payment.facade.payment.PaymentFacade",
            "com.qoder.payment.facade.trade.TradeFacade",
            "com.qoder.payment.facade.member.MemberFacade",
            "com.qoder.payment.facade.account.AccountFacade"
    };

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final boolean enabled;
    private final Path rulesFile;
    private final boolean apolloEnabled;
    private final String apolloNamespace;
    private final String apolloKey;
    private final String applicationName;

    private ScheduledExecutorService poller;
    private long lastModified = -1;
    private long lastLength = -1;
    private String lastApolloJson;
    private boolean apolloBroken;
    private boolean degradedToDefaults;

    public SentinelRuleLoader(Environment env) {
        this.enabled = env.getProperty("payment.sentinel.enabled", Boolean.class, true);
        String file = env.getProperty("payment.sentinel.rules.file",
                System.getProperty("user.home") + "/.config/payment-pro/sentinel-rules.json");
        this.rulesFile = Paths.get(file);
        this.apolloEnabled = env.getProperty("payment.sentinel.apollo.enabled", Boolean.class, false);
        this.apolloNamespace = env.getProperty("payment.sentinel.apollo.namespace", "sentinel-rules");
        this.apolloKey = env.getProperty("payment.sentinel.apollo.key", "rules");
        this.applicationName = env.getProperty("spring.application.name", "payment-pro");
    }

    @Override
    public void afterPropertiesSet() {
        // dashboard 未配置时 transport 不建任何连接（local 零依赖）；仅标识应用名供后续接入。
        if (System.getProperty("csp.sentinel.app.name") == null) {
            System.setProperty("csp.sentinel.app.name", applicationName);
        }
        if (!enabled) {
            log.info("Sentinel 规则加载已关闭（payment.sentinel.enabled=false），默认放行");
            applyDefaults("disabled");
            return;
        }
        reload();
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sentinel-rule-loader");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::safeReload, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Sentinel 规则数据源就绪 file={} apollo={} poll={}s", rulesFile, apolloEnabled && !apolloBroken, POLL_INTERVAL_SECONDS);
    }

    @Override
    public void destroy() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    private void safeReload() {
        try {
            reload();
        } catch (Throwable t) {
            // 轮询线程永不因加载失败而终止
            log.warn("Sentinel 规则轮询异常（保持现行规则）: {}", t.toString());
        }
    }

    private void reload() {
        if (apolloEnabled && !apolloBroken) {
            String json = readApollo();
            if (json != null) {
                if (!json.equals(lastApolloJson)) {
                    lastApolloJson = json;
                    applyJson(json, "apollo:" + apolloNamespace);
                }
                return;
            }
            // Apollo 不可用 → 本进程生命周期内回落文件（I-2 部署后重启即恢复 Apollo 源）
        }
        long mtime;
        long size;
        try {
            if (!Files.isRegularFile(rulesFile)) {
                if (lastModified != -2) {
                    lastModified = -2;
                    lastLength = -1;
                    applyDefaults("规则文件缺失 " + rulesFile);
                }
                return;
            }
            mtime = Files.getLastModifiedTime(rulesFile).toMillis();
            size = Files.size(rulesFile);
        } catch (IOException e) {
            applyDefaults("规则文件不可读 " + rulesFile + ": " + e);
            return;
        }
        if (mtime == lastModified && size == lastLength) {
            return;
        }
        lastModified = mtime;
        lastLength = size;
        try {
            applyJson(Files.readString(rulesFile), "file:" + rulesFile);
        } catch (Exception e) {
            applyDefaults("规则文件损坏 " + rulesFile + ": " + e);
        }
    }

    /** 反射读 Apollo（不硬依赖 apollo-client；E 泳道 I-2 落地后配置开关即启用）。 */
    private String readApollo() {
        try {
            Class<?> configService = Class.forName("com.ctrip.framework.apollo.ConfigService");
            Object config = configService.getMethod("getConfig", String.class).invoke(null, apolloNamespace);
            Object value = config.getClass().getMethod("getProperty", String.class, String.class)
                    .invoke(config, apolloKey, null);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            apolloBroken = true;
            log.warn("Apollo 规则源不可用，回落本地文件 {}: {}", rulesFile, t.toString());
            return null;
        }
    }

    private void applyJson(String json, String source) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("规则文档必须是 JSON 对象");
            }
            List<FlowRule> flow = parseRules(root.get("flowRules"), FlowRule.class);
            List<ParamFlowRule> param = parseRules(root.get("paramFlowRules"), ParamFlowRule.class);
            List<DegradeRule> degrade = root.has("degradeRules")
                    ? parseRules(root.get("degradeRules"), DegradeRule.class)
                    : defaultDegradeRules();
            flow.removeIf(r -> !validResource(r.getResource()) || r.getCount() <= 0);
            param.removeIf(r -> !validResource(r.getResource()) || r.getCount() <= 0
                    || r.getParamIdx() == null || r.getParamIdx() < 0);
            degrade.removeIf(r -> !validResource(r.getResource()) || r.getCount() <= 0 || r.getTimeWindow() <= 0);
            FlowRuleManager.loadRules(flow);
            ParamFlowRuleManager.loadRules(param);
            DegradeRuleManager.loadRules(degrade);
            degradedToDefaults = false;
            log.info("Sentinel 规则已加载 [{}] flow={} param={} degrade={}", source, flow.size(), param.size(), degrade.size());
        } catch (Exception e) {
            applyDefaults("规则解析失败 [" + source + "]: " + e);
        }
    }

    private <T> List<T> parseRules(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(type.getSimpleName() + " 必须是数组");
        }
        List<T> rules = new ArrayList<>();
        for (JsonNode item : node) {
            rules.add(mapper.convertValue(item, type));
        }
        return rules;
    }

    /**
   * 降级基线：清空全部限流（默认放行，不阻断支付），保留内置保守熔断。
   * 只在状态翻转时打 WARN，避免轮询刷屏。
   */
    private void applyDefaults(String reason) {
        try {
            FlowRuleManager.loadRules(Collections.emptyList());
            ParamFlowRuleManager.loadRules(Collections.emptyList());
            DegradeRuleManager.loadRules(defaultDegradeRules());
            if (!degradedToDefaults) {
                degradedToDefaults = true;
                log.warn("Sentinel 规则降级为默认放行（不阻断支付）: {}", reason);
            }
        } catch (Throwable t) {
            log.error("Sentinel 默认规则加载失败（放行兜底）: {}", t.toString());
        }
    }

    private static List<DegradeRule> defaultDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        for (String resource : DEFAULT_DEGRADE_RESOURCES) {
            DegradeRule rule = new DegradeRule(resource);
            rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
            rule.setCount(5);
            rule.setTimeWindow(10);
            rule.setMinRequestAmount(5);
            rule.setStatIntervalMs(10_000);
            rules.add(rule);
        }
        return rules;
    }

    private static boolean validResource(String resource) {
        return resource != null && !resource.isBlank();
    }
}