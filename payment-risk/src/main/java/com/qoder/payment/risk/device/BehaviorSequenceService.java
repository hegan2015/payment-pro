package com.qoder.payment.risk.device;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.BehaviorEventDTO;
import com.qoder.payment.risk.config.RiskPlusKeys;
import com.qoder.payment.risk.engine.RiskContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R-5 行为序列（支付行为时序）：短时间多笔（burst）/ 异地跳变（geo jump）识别。
 *
 * <p>样本来自受理链路旁路观测（{@code behavior_sequence}），只 INSERT；
 * 分析结果供设备画像 BURST 信号与运营查询使用，<b>不直接拦截交易</b>。
 * <p>默认窗口 60s / 上限 20 笔（远高于内置限频 10s×5 笔口径），正常流量不触发。
 */
@Slf4j
@Service
public class BehaviorSequenceService {

    private final DeviceStore store;
    private final DynamicConfig config;
    private final SandboxIpDataSource ipSource;

    public BehaviorSequenceService(DeviceStore store, DynamicConfig config, SandboxIpDataSource ipSource) {
        this.store = store;
        this.config = config;
        this.ipSource = ipSource;
    }

    public boolean enabled() {
        return config.getBool(RiskPlusKeys.BEHAVIOR_ENABLED, RiskPlusKeys.DEFAULT_BEHAVIOR_ENABLED);
    }

    public long windowMillis() {
        long v = config.getLong(RiskPlusKeys.BEHAVIOR_WINDOW, RiskPlusKeys.DEFAULT_BEHAVIOR_WINDOW);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_BEHAVIOR_WINDOW;
    }

    public int maxCount() {
        int v = config.getInt(RiskPlusKeys.BEHAVIOR_MAX, RiskPlusKeys.DEFAULT_BEHAVIOR_MAX);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_BEHAVIOR_MAX;
    }

    public int retentionDays() {
        int v = config.getInt(RiskPlusKeys.BEHAVIOR_RETENTION_DAYS, RiskPlusKeys.DEFAULT_BEHAVIOR_RETENTION_DAYS);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_BEHAVIOR_RETENTION_DAYS;
    }

    /** 旁路记录一次行为（deviceId/ip 全空则跳过；ride/smoke 真实支付即此路径）。 */
    public void observe(RiskContext ctx) {
        if (!enabled() || ctx.getPayerId().isBlank()) {
            return;
        }
        if (ctx.getDeviceId().isBlank() && ctx.getIp().isBlank()) {
            return;
        }
        SandboxIpDataSource.Entry e = ipSource.resolve(ctx.getIp());
        BehaviorEventDTO d = new BehaviorEventDTO();
        d.setPayerId(ctx.getPayerId());
        d.setDeviceId(ctx.getDeviceId().isBlank() ? null : deviceKey(ctx.getDeviceId()));
        d.setIp(ctx.getIp().isBlank() ? null : ctx.getIp());
        d.setRegion(e.region());
        d.setCountry(e.country());
        d.setAction(ctx.getScene());
        d.setAmount(ctx.getAmount());
        d.setTraceId(ctx.getTraceId());
        d.setCreatedAt(ctx.getNow());
        store.insertBehavior(d);
    }

    /** 与 DeviceProfileService 同一脱敏口径（避免循环依赖，此处独立计算）。 */
    private String deviceKey(String raw) {
        boolean mask = config.getBool(RiskPlusKeys.DEVICE_MASK, RiskPlusKeys.DEFAULT_DEVICE_MASK);
        return mask ? "D" + DeviceProfileService.sha256(raw.trim()).substring(0, 24) : raw.trim();
    }

    /** 窗口内是否突发（笔数 ≥ 上限）。查询失败按「无信息」处理，绝不因观测故障加分。 */
    public boolean burst(String payerId, String deviceKey, long now) {
        if (!enabled()) {
            return false;
        }
        try {
            List<BehaviorEventDTO> events = store.window(payerId, deviceKey, now - windowMillis(), 500);
            return events.size() >= maxCount();
        } catch (Exception e) {
            log.warn("行为序列突发判定失败（按无信息处理）: {}", e.getMessage());
            return false;
        }
    }

    /** 行为序列异常分析：窗口笔数 / 地域跳变 / 突发。 */
    public Map<String, Object> analyze(String payerId, String rawDeviceId, Long window) {
        long w = window == null || window <= 0 ? windowMillis() : window;
        long now = System.currentTimeMillis();
        String key = rawDeviceId == null || rawDeviceId.isBlank() ? null : deviceKey(rawDeviceId);
        List<BehaviorEventDTO> events = store.window(payerId, key, now - w, 1000);
        Set<String> regions = new LinkedHashSet<>();
        Set<String> countries = new LinkedHashSet<>();
        long amount = 0;
        for (BehaviorEventDTO e : events) {
            if (e.getRegion() != null && !e.getRegion().isBlank()) {
                regions.add(e.getRegion());
            }
            if (e.getCountry() != null && !e.getCountry().isBlank()) {
                countries.add(e.getCountry());
            }
            amount += e.getAmount() == null ? 0 : e.getAmount();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payerId", payerId);
        m.put("deviceId", key);
        m.put("windowMillis", w);
        m.put("count", events.size());
        m.put("amount", amount);
        m.put("regions", regions);
        m.put("countries", countries);
        m.put("geoJump", regions.size() > 1);
        m.put("crossBorder", countries.size() > 1);
        m.put("burst", events.size() >= maxCount());
        m.put("maxCount", maxCount());
        return m;
    }

    public List<BehaviorEventDTO> list(String payerId, int limit) {
        return store.listBehavior(payerId, limit);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behaviorEnabled", enabled());
        m.put("behaviorWindowMillis", windowMillis());
        m.put("behaviorMaxCount", maxCount());
        m.put("behaviorRetentionDays", retentionDays());
        m.put("behaviorCount", store.behaviorCount());
        return m;
    }
}