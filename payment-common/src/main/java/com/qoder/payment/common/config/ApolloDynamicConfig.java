package com.qoder.payment.common.config;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Apollo 动态配置（docker profile，{@code payment.config.apollo.enabled=true}）。
 *
 * <p>namespace 划分（I-2）：{@code application}（公共费率/开关）、{@code risk-rules}（R-1）、
 * {@code channel-route}（CH-3）、{@code settle-fee}（ST-2）；按声明顺序取第一个命中值。
 *
 * <p><b>降级红线（与 MerchantCache 一致，不 fail-fast）</b>：
 * <ul>
 *   <li>namespace 初始化在守护线程异步执行——Apollo 不可达绝不阻塞应用启动与支付受理；</li>
 *   <li>apollo-client 自带本地缓存文件（last-known-good，{@code apollo.cacheDir}）+ 长轮询自动追平；</li>
 *   <li>任何读取/初始化异常一律吞掉 → 代码默认值兜底，只 warn 不抛。</li>
 * </ul>
 *
 * <p>密钥类配置不进 Apollo 明文（走 SecretProvider，S-1）。
 * 连接参数经系统属性注入（app.id / apollo.meta / env / apollo.cluster / apollo.cacheDir），
 * 已有 -D 覆盖优先；不启用 Spring 属性注入（apollo.bootstrap 不开），避免与既有 Environment 耦合。
 */
@Slf4j
public class ApolloDynamicConfig implements DynamicConfig {

    public static final String DEFAULT_NAMESPACES = "application,risk-rules,channel-route,settle-fee";
    private static final long CACHE_REFRESH_SECONDS = 30;

    private final String appId;
    private final String meta;
    private final List<String> namespaces;
    private final Map<String, Config> configs = new ConcurrentHashMap<>();
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private volatile Map<String, String> cache = Map.of();
    private volatile boolean remoteLoaded;
    private volatile String initError;
    private final ScheduledExecutorService scheduler;

    public ApolloDynamicConfig(String appId, String meta, String env, String cluster, String namespaces) {
        this.appId = appId == null || appId.isBlank() ? "payment-pro" : appId.trim();
        this.meta = meta == null || meta.isBlank() ? "http://127.0.0.1:18070" : meta.trim();
        this.namespaces = parseNamespaces(namespaces);
        setIfAbsent("app.id", this.appId);
        setIfAbsent("apollo.meta", this.meta);
        setIfAbsent("env", env == null || env.isBlank() ? "DEV" : env.trim());
        setIfAbsent("apollo.cluster", cluster == null || cluster.isBlank() ? "default" : cluster.trim());
        setIfAbsent("apollo.cacheDir",
                Path.of(System.getProperty("user.home"), ".config", "payment-pro", "apollo-cache").toString());
        setIfAbsent("apollo.autoUpdateInjectedSpringProperties", "false");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "apollo-config-maintenance");
            t.setDaemon(true);
            return t;
        });
        // 异步初始化：配置中心不可达时启动/支付不受影响（降级红线）
        scheduler.execute(this::initNamespaces);
        scheduler.scheduleWithFixedDelay(this::refreshCacheQuietly,
                CACHE_REFRESH_SECONDS, CACHE_REFRESH_SECONDS, TimeUnit.SECONDS);
        log.info("ApolloDynamicConfig 启用 appId={} meta={} namespaces={}（异步初始化，不可达自动降级默认值）",
                this.appId, this.meta, this.namespaces);
    }

    private static List<String> parseNamespaces(String raw) {
        List<String> list = new ArrayList<>();
        for (String ns : (raw == null || raw.isBlank() ? DEFAULT_NAMESPACES : raw).split(",")) {
            String t = ns.trim();
            if (!t.isEmpty() && !list.contains(t)) {
                list.add(t);
            }
        }
        return Collections.unmodifiableList(list);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
            System.setProperty(key, value);
        }
    }

    private void initNamespaces() {
        StringBuilder errors = new StringBuilder();
        for (String ns : namespaces) {
            if (configs.containsKey(ns)) {
                continue;
            }
            try {
                Config config = ConfigService.getConfig(ns);
                config.addChangeListener(this::onApolloChange);
                configs.put(ns, config);
                log.info("Apollo namespace 就绪 ns={} keys={}", ns, config.getPropertyNames().size());
            } catch (Throwable t) {
                // 不可达/缺 app.id 等：记录并继续，读取侧默认值兜底，绝不阻断支付
                log.warn("Apollo namespace 初始化失败 ns={}（降级默认值/last-known-good）: {}", ns, t.getMessage());
                if (errors.length() > 0) {
                    errors.append("; ");
                }
                errors.append(ns).append(": ").append(t.getMessage());
            }
        }
        remoteLoaded = !configs.isEmpty();
        initError = errors.length() == 0 ? null : errors.toString();
        rebuildCache();
    }

    private void onApolloChange(ConfigChangeEvent event) {
        try {
            Map<String, String> prev = cache;
            rebuildCache();
            Map<String, String> next = cache;
            for (String key : event.changedKeys()) {
                ConfigChange change = event.getChange(key);
                String oldV = change == null ? prev.get(key) : change.getOldValue();
                String newV = change == null ? next.get(key) : change.getNewValue();
                fire(key, oldV, newV);
            }
            log.info("Apollo 配置变更 namespace={} keys={}", event.getNamespace(), event.changedKeys());
        } catch (Exception e) {
            log.warn("Apollo 变更处理异常（忽略，长轮询会追平）: {}", e.getMessage());
        }
    }

    /** 从各 namespace 当前属性重建快照（application 在前，先命中优先）。 */
    private synchronized void rebuildCache() {
        Map<String, String> next = new LinkedHashMap<>();
        for (String ns : namespaces) {
            Config config = configs.get(ns);
            if (config == null) {
                continue;
            }
            try {
                for (String key : config.getPropertyNames()) {
                    next.putIfAbsent(key, config.getProperty(key, null));
                }
            } catch (Exception e) {
                log.warn("Apollo 快照读取失败 ns={}: {}", ns, e.getMessage());
            }
        }
        next.values().removeIf(Objects::isNull);
        cache = Collections.unmodifiableMap(next);
    }

    private void refreshCacheQuietly() {
        try {
            if (configs.size() < namespaces.size()) {
                initNamespaces();
            }
            Map<String, String> prev = cache;
            rebuildCache();
            for (Map.Entry<String, List<Listener>> e : listeners.entrySet()) {
                String oldV = prev.get(e.getKey());
                String newV = cache.get(e.getKey());
                if (!Objects.equals(oldV, newV)) {
                    fire(e.getKey(), oldV, newV);
                }
            }
        } catch (Exception e) {
            log.warn("Apollo 缓存维护异常（忽略）: {}", e.getMessage());
        }
    }

    private void fire(String key, String oldV, String newV) {
        List<Listener> list = listeners.get(key);
        if (list == null) {
            return;
        }
        for (Listener listener : list) {
            try {
                listener.onChange(key, oldV, newV);
            } catch (Exception ex) {
                log.warn("动态配置监听回调异常 key={}: {}", key, ex.getMessage());
            }
        }
    }

    @Override
    public String get(String key, String def) {
        try {
            for (String ns : namespaces) {
                Config config = configs.get(ns);
                if (config != null) {
                    String v = config.getProperty(key, null);
                    if (v != null && !v.isBlank()) {
                        return v.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Apollo 读取失败 key={}（默认值兜底）: {}", key, e.getMessage());
        }
        return def;
    }

    @Override
    public long getLong(String key, long def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            log.warn("Apollo 配置解析失败 key={} value={}，用默认值 {}", key, v, def);
            return def;
        }
    }

    @Override
    public int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            log.warn("Apollo 配置解析失败 key={} value={}，用默认值 {}", key, v, def);
            return def;
        }
    }

    @Override
    public boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        log.warn("Apollo 配置布尔解析失败 key={} value={}，用默认值 {}", key, v, def);
        return def;
    }

    @Override
    public void addListener(String key, Listener listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public Map<String, String> snapshot() {
        return cache;
    }

    @Override
    public boolean refresh() {
        // Apollo 长轮询自动推送，无需手动 reload；这里仅重建快照供控制台观测
        refreshCacheQuietly();
        return false;
    }

    @Override
    public String provider() {
        return "apollo";
    }

    public String appId() {
        return appId;
    }

    public String meta() {
        return meta;
    }

    public List<String> namespaces() {
        return namespaces;
    }

    /** 是否至少一个 namespace 初始化成功（false=完全降级默认值/缓存态）。 */
    public boolean remoteLoaded() {
        return remoteLoaded;
    }

    /** 初始化错误摘要（null=无错误）。 */
    public String initError() {
        return initError;
    }

    /** Bean destroy：停维护线程（apollo-client 内部线程随进程退出）。 */
    public void close() {
        scheduler.shutdownNow();
    }
}