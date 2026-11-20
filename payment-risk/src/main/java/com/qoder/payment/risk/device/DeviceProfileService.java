package com.qoder.payment.risk.device;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.DeviceAccountDTO;
import com.qoder.payment.facade.risk.DeviceProfileDTO;
import com.qoder.payment.risk.config.RiskPlusKeys;
import com.qoder.payment.risk.engine.RiskContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-5 设备指纹采集与稳定性判定 + 设备-账户关联图谱（<b>沙箱，不接真实设备 SDK</b>）。
 *
 * <p>采集面：端侧上报 {@code deviceId} + 可选 {@code deviceOs/deviceModel/deviceUa}
 * （{@code RiskCheckRequest} additive 字段）。属性组合哈希变化即「指纹漂移」，
 * 漂移次数 ≥ 阈值判 UNSTABLE；同设备关联账户数 ≥ 阈值判 MULTI_ACCOUNT；
 * 行为序列窗口内突发判 BURST。
 *
 * <p><b>接入既有评分</b>：判定结果写入 {@link RiskContext} 的 DEVICE 因子观测位，
 * 由既有 {@code RiskScoreService} 的 <b>DEVICE 因子（权重 25 不变）</b>消费，
 * 并经既有 {@code RiskFunctions.deviceRisk} 供 QLExpress 规则使用——<b>不另起评分体系</b>。
 * <p><b>默认影子</b>：{@code risk.device.profile-score-enabled=false} → 只采集不加分。
 * <p><b>隐私</b>：默认脱敏存储（SHA-256 键 + 首尾掩码），原始设备标识永不落库/永不返回；
 * 留存期 {@code risk.device.retention-days} 可配，到期<b>匿名化</b>不删行。
 */
@Slf4j
@Service
public class DeviceProfileService {

    private final DeviceStore store;
    private final DynamicConfig config;
    private final BehaviorSequenceService behavior;

    public DeviceProfileService(DeviceStore store, DynamicConfig config, BehaviorSequenceService behavior) {
        this.store = store;
        this.config = config;
        this.behavior = behavior;
    }

    public boolean enabled() {
        return config.getBool(RiskPlusKeys.DEVICE_ENABLED, RiskPlusKeys.DEFAULT_DEVICE_ENABLED);
    }

    public boolean scoreEnabled() {
        return config.getBool(RiskPlusKeys.DEVICE_PROFILE_SCORE, RiskPlusKeys.DEFAULT_DEVICE_PROFILE_SCORE);
    }

    public boolean maskEnabled() {
        return config.getBool(RiskPlusKeys.DEVICE_MASK, RiskPlusKeys.DEFAULT_DEVICE_MASK);
    }

    public int multiAccountThreshold() {
        return positive(config.getInt(RiskPlusKeys.DEVICE_MULTI_ACCOUNT, RiskPlusKeys.DEFAULT_DEVICE_MULTI_ACCOUNT),
                RiskPlusKeys.DEFAULT_DEVICE_MULTI_ACCOUNT);
    }

    public int driftThreshold() {
        return positive(config.getInt(RiskPlusKeys.DEVICE_DRIFT, RiskPlusKeys.DEFAULT_DEVICE_DRIFT),
                RiskPlusKeys.DEFAULT_DEVICE_DRIFT);
    }

    public int retentionDays() {
        return positive(config.getInt(RiskPlusKeys.DEVICE_RETENTION_DAYS, RiskPlusKeys.DEFAULT_DEVICE_RETENTION_DAYS),
                RiskPlusKeys.DEFAULT_DEVICE_RETENTION_DAYS);
    }

    private static int positive(int v, int def) {
        return v > 0 ? v : def;
    }

    /** 脱敏存储键（写入与查询共用，保证同一原始 id 稳定映射同一行）。 */
    public String storageKey(String rawDeviceId) {
        if (rawDeviceId == null || rawDeviceId.isBlank()) {
            return "";
        }
        String raw = rawDeviceId.trim();
        return maskEnabled() ? "D" + sha256(raw).substring(0, 24) : raw;
    }

    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.length() <= 8) {
            return s.substring(0, Math.min(2, s.length())) + "****";
        }
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 端侧属性指纹（os|model|ua）；三者全空 → 空串（无属性信息，不参与漂移判定）。 */
    public static String fingerprint(String os, String model, String ua) {
        String joined = nz(os) + "|" + nz(model) + "|" + nz(ua);
        return joined.equals("||") ? "" : sha256(joined).substring(0, 32);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    // ---------------- 采集（受理链路旁路，失败只 warn） ----------------

    /** 观测一次交易：更新设备画像 + 关联图谱边。deviceId 为空直接跳过（ride/smoke 真实支付即此路径）。 */
    public void observe(RiskContext ctx) {
        if (!enabled() || ctx.getDeviceId() == null || ctx.getDeviceId().isBlank()) {
            return;
        }
        String key = storageKey(ctx.getDeviceId());
        String fp = fingerprint(ctx.getDeviceOs(), ctx.getDeviceModel(), ctx.getDeviceUa());
        long now = ctx.getNow();
        DeviceProfileDTO old = store.getDevice(key);
        int drift = old == null || old.getDriftCount() == null ? 0 : old.getDriftCount();
        if (old != null && fp != null && !fp.isEmpty()
                && old.getFingerprintHash() != null && !old.getFingerprintHash().isEmpty()
                && !fp.equals(old.getFingerprintHash())) {
            drift++;
        }
        if (!ctx.getPayerId().isBlank()) {
            store.touchRel(key, ctx.getPayerId());
        }
        int accounts = store.countAccounts(key);
        DeviceProfileDTO d = new DeviceProfileDTO();
        d.setDeviceId(key);
        d.setDeviceMask(mask(ctx.getDeviceId()));
        d.setFingerprintHash(fp == null || fp.isEmpty() ? (old == null ? null : old.getFingerprintHash()) : fp);
        d.setDriftCount(drift);
        d.setStability(stability(drift, d.getFingerprintHash()));
        d.setFirstSeen(old == null || old.getFirstSeen() == null ? now : old.getFirstSeen());
        d.setLastSeen(now);
        d.setAccountCount(accounts);
        d.setRetentionUntil(now + retentionDays() * 86_400_000L);
        boolean burst = behavior.burst(ctx.getPayerId(), key, now);
        Judge j = judge(accounts, drift, burst);
        d.setRiskScore(j.score());
        d.setTags(j.tagsJson());
        d.setDetail(j.detail());
        store.saveDevice(d);
    }

    private String stability(int drift, String fp) {
        if (fp == null || fp.isEmpty()) {
            return "UNKNOWN";
        }
        if (drift >= driftThreshold()) {
            return "UNSTABLE";
        }
        return drift > 0 ? "DRIFTING" : "STABLE";
    }

    /** 画像判定结果（供既有 DEVICE 因子消费）。 */
    public record Judge(int score, List<String> signals, String detail) {
        String tagsJson() {
            return "[" + String.join(",", signals.stream().map(s -> "\"" + s + "\"").toList()) + "]";
        }
    }

    public Judge judge(int accounts, int drift, boolean burst) {
        List<String> signals = new ArrayList<>();
        List<String> why = new ArrayList<>();
        if (accounts >= multiAccountThreshold()) {
            signals.add("MULTI_ACCOUNT");
            why.add("同设备关联账户 " + accounts + " ≥ " + multiAccountThreshold());
        }
        if (drift >= driftThreshold()) {
            signals.add("UNSTABLE");
            why.add("指纹漂移 " + drift + " 次 ≥ " + driftThreshold());
        }
        if (burst) {
            signals.add("BURST");
            why.add("行为序列窗口内突发");
        }
        int score = Math.min(RiskPlusKeys.PROFILE_RISK_SCORE, signals.size() * 40);
        return new Judge(score, signals, signals.isEmpty()
                ? "设备画像正常（账户数 " + accounts + "，漂移 " + drift + "）"
                : String.join("；", why));
    }

    // ---------------- 接入既有 DEVICE 因子（只在开关开启时查库） ----------------

    /** 把画像观测写入 ctx，供既有 RiskScoreService DEVICE 因子与 RiskFunctions.deviceRisk 使用。 */
    public void loadInto(RiskContext ctx) {
        if (!enabled() || !scoreEnabled() || ctx.getDeviceId().isBlank()) {
            return;
        }
        String key = storageKey(ctx.getDeviceId());
        DeviceProfileDTO d = store.getDevice(key);
        ctx.setDeviceProfileLoaded(true);
        if (d == null) {
            ctx.setDeviceProfileDetail("设备无历史画像样本（首次 sighting，不加分）");
            return;
        }
        int accounts = d.getAccountCount() == null ? 0 : d.getAccountCount();
        int drift = d.getDriftCount() == null ? 0 : d.getDriftCount();
        boolean burst = behavior.burst(ctx.getPayerId(), key, ctx.getNow());
        Judge j = judge(accounts, drift, burst);
        ctx.setDeviceAccountCount(accounts);
        ctx.setDeviceDriftCount(drift);
        ctx.setDeviceStability(d.getStability() == null ? "UNKNOWN" : d.getStability());
        ctx.setDeviceRiskScore(j.score());
        ctx.setDeviceProfileRisk(j.score() > 0);
        ctx.setDeviceProfileDetail(j.detail());
    }

    // ---------------- 运营查询 ----------------

    public DeviceProfileDTO profile(String rawDeviceId) {
        String key = storageKey(rawDeviceId);
        DeviceProfileDTO d = key.isEmpty() ? null : store.getDevice(key);
        if (d != null) {
            return d;
        }
        DeviceProfileDTO empty = new DeviceProfileDTO();
        empty.setDeviceId(key);
        empty.setDeviceMask(mask(rawDeviceId));
        empty.setStability("UNKNOWN");
        empty.setDriftCount(0);
        empty.setAccountCount(0);
        empty.setRiskScore(0);
        empty.setRisky(false);
        empty.setTags("[]");
        empty.setExpired(false);
        empty.setDetail("无采集样本（沙箱未上报该设备）");
        return empty;
    }

    public List<DeviceAccountDTO> deviceAccounts(String rawDeviceId) {
        return store.listDeviceAccounts(storageKey(rawDeviceId));
    }

    public List<DeviceAccountDTO> accountDevices(String accountId) {
        return store.listAccountDevices(accountId);
    }

    /** 留存期执行：到期匿名化（不 DELETE）。beforeMillis 为空取当前时间。 */
    public Map<String, Object> applyRetention(String operator, Long beforeMillis) {
        long now = beforeMillis == null || beforeMillis <= 0 ? System.currentTimeMillis() : beforeMillis;
        Map<String, Object> m = new LinkedHashMap<>();
        int devices = store.expireDevices(now);
        int ips = store.expireIps(now);
        int behaviors = store.expireBehavior(now - retentionDays() * 86_400_000L);
        m.put("operator", operator);
        m.put("beforeMillis", now);
        m.put("deviceRetentionDays", retentionDays());
        m.put("anonymizedDevices", devices);
        m.put("anonymizedIps", ips);
        m.put("anonymizedBehaviors", behaviors);
        m.put("mode", "ANONYMIZE");
        m.put("deletedRows", 0);
        log.info("R-5 留存期匿名化 operator={} before={} devices={} ips={} behaviors={}（不删行）",
                operator, now, devices, ips, behaviors);
        return m;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceEnabled", enabled());
        m.put("deviceProfileScoreEnabled", scoreEnabled());
        m.put("maskEnabled", maskEnabled());
        m.put("multiAccountThreshold", multiAccountThreshold());
        m.put("driftThreshold", driftThreshold());
        m.put("deviceRetentionDays", retentionDays());
        m.put("deviceCount", store.deviceCount());
        m.put("deviceAccountRelCount", store.relCount());
        return m;
    }
}