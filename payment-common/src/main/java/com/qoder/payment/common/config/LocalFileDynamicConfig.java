package com.qoder.payment.common.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件动态配置（local profile 默认，零依赖）：读 {@code ~/.config/payment-pro/config.properties}。
 * <ul>
 *   <li>启动即加载 + 每 30s 定时 reload（守护线程），{@link #refresh()} 供控制台手动 reload；</li>
 *   <li><b>文件缺失 → 空快照（代码默认值兜底），不报错</b>；读取失败（IO 异常）→ 保留 last-known-good；</li>
 *   <li>值解析失败 → 默认值 + warn，绝不抛异常阻断支付（降级红线）；</li>
 *   <li>变更按 key 回调 {@link Listener}（新增/修改/删除，删除 newValue=null）。</li>
 * </ul>
 * 文件建议 600 权限（密钥类配置不放这里，走 SecretProvider）。
 */
@Slf4j
public class LocalFileDynamicConfig implements DynamicConfig {

    public static final long RELOAD_INTERVAL_SECONDS = 30;

    private final Path file;
    private volatile Map<String, String> values = Map.of();
    private volatile boolean fileExists;
    private volatile long lastReloadAt;
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public LocalFileDynamicConfig() {
        this(Path.of(System.getProperty("user.home"), ".config", "payment-pro", "config.properties"));
    }

    public LocalFileDynamicConfig(Path file) {
        this.file = file;
        reload();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dynamic-config-reload");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::reloadQuietly,
                RELOAD_INTERVAL_SECONDS, RELOAD_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("LocalFileDynamicConfig 启用 file={} entries={} reload={}s", file, values.size(), RELOAD_INTERVAL_SECONDS);
    }

    @Override
    public String get(String key, String def) {
        String v = values.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    @Override
    public long getLong(String key, long def) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("动态配置解析失败 key={} value={}，用默认值 {}", key, v, def);
            return def;
        }
    }

    @Override
    public int getInt(String key, int def) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("动态配置解析失败 key={} value={}，用默认值 {}", key, v, def);
            return def;
        }
    }

    @Override
    public boolean getBool(String key, boolean def) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        String t = v.trim();
        if ("true".equalsIgnoreCase(t)) {
            return true;
        }
        if ("false".equalsIgnoreCase(t)) {
            return false;
        }
        log.warn("动态配置布尔解析失败 key={} value={}，用默认值 {}", key, v, def);
        return def;
    }

    @Override
    public void addListener(String key, Listener listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public Map<String, String> snapshot() {
        return values;
    }

    @Override
    public boolean refresh() {
        return reload();
    }

    @Override
    public String provider() {
        return "local-file";
    }

    /** 配置文件路径（控制台观测用）。 */
    public Path file() {
        return file;
    }

    /** 文件当前是否存在（缺失=默认值兜底态）。 */
    public boolean fileExists() {
        return fileExists;
    }

    /** 最近一次成功 reload 时间戳（毫秒）。 */
    public long lastReloadAt() {
        return lastReloadAt;
    }

    /** 重读文件：缺失→空快照（默认值兜底）；IO 失败→保留 last-known-good。返回是否成功加载。 */
    public synchronized boolean reload() {
        boolean exists = Files.isReadable(file);
        Map<String, String> next = new TreeMap<>();
        if (exists) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("动态配置文件读取失败，保留 last-known-good file={}: {}", file, e.getMessage());
                return false;
            }
            for (String name : props.stringPropertyNames()) {
                String v = props.getProperty(name);
                if (v != null) {
                    next.put(name, v.trim());
                }
            }
        }
        Map<String, String> prev = values;
        values = Collections.unmodifiableMap(next);
        fileExists = exists;
        lastReloadAt = System.currentTimeMillis();
        fireChanges(prev, next);
        return true;
    }

    private void reloadQuietly() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("动态配置定时 reload 异常（保留 last-known-good）: {}", e.getMessage());
        }
    }

    private void fireChanges(Map<String, String> prev, Map<String, String> next) {
        for (Map.Entry<String, List<Listener>> e : listeners.entrySet()) {
            String key = e.getKey();
            String oldV = prev.get(key);
            String newV = next.get(key);
            if (Objects.equals(oldV, newV)) {
                continue;
            }
            for (Listener listener : e.getValue()) {
                try {
                    listener.onChange(key, oldV, newV);
                } catch (Exception ex) {
                    log.warn("动态配置监听回调异常 key={}: {}", key, ex.getMessage());
                }
            }
        }
    }

    /** 停定时 reload（Bean destroy）。 */
    public void close() {
        scheduler.shutdownNow();
    }
}