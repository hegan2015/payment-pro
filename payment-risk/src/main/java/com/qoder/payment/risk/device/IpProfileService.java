package com.qoder.payment.risk.device;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.IpProfileDTO;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.risk.config.RiskPlusKeys;
import com.qoder.payment.risk.engine.RiskContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-5 IP 画像：归属地 / 代理 / IDC / 历史风险 / 关联账户数。
 *
 * <p><b>离线沙箱数据源</b>（{@link SandboxIpDataSource}），不连外部威胁情报 API；
 * 历史风险来自本域 {@code risk_decision_log} 的拒绝留痕（自有数据，非外部情报）。
 * <p><b>接入既有评分</b>：判定结果写入 {@link RiskContext} 的 IP 因子观测位，由既有
 * {@code RiskScoreService} 的 <b>IP 因子（权重 20 不变）</b>消费，并经既有
 * {@code RiskFunctions.ipRisk} 供 QLExpress 规则使用——<b>不另起评分体系</b>。
 * <p><b>默认影子</b>：{@code risk.ip.profile-score-enabled=false} → 只采集不加分。
 */
@Slf4j
@Service
public class IpProfileService {

    private final DeviceStore store;
    private final DynamicConfig config;
    private final SandboxIpDataSource source;
    private final JdbcTemplate jdbc;

    public IpProfileService(DeviceStore store, DynamicConfig config, SandboxIpDataSource source, JdbcTemplate jdbc) {
        this.store = store;
        this.config = config;
        this.source = source;
        this.jdbc = jdbc;
    }

    public boolean enabled() {
        return config.getBool(RiskPlusKeys.DEVICE_ENABLED, RiskPlusKeys.DEFAULT_DEVICE_ENABLED);
    }

    public boolean scoreEnabled() {
        return config.getBool(RiskPlusKeys.IP_PROFILE_SCORE, RiskPlusKeys.DEFAULT_IP_PROFILE_SCORE);
    }

    public int multiAccountThreshold() {
        int v = config.getInt(RiskPlusKeys.IP_MULTI_ACCOUNT, RiskPlusKeys.DEFAULT_IP_MULTI_ACCOUNT);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_IP_MULTI_ACCOUNT;
    }

    public int rejectThreshold() {
        int v = config.getInt(RiskPlusKeys.IP_REJECT_COUNT, RiskPlusKeys.DEFAULT_IP_REJECT_COUNT);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_IP_REJECT_COUNT;
    }

    public int retentionDays() {
        int v = config.getInt(RiskPlusKeys.IP_RETENTION_DAYS, RiskPlusKeys.DEFAULT_IP_RETENTION_DAYS);
        return v > 0 ? v : RiskPlusKeys.DEFAULT_IP_RETENTION_DAYS;
    }

    /** 离线沙箱归属地解析（AML 跨境规则复用，纯内存无网络）。 */
    public SandboxIpDataSource.Entry resolve(String ip) {
        return source.resolve(ip);
    }

    public String sourceName() {
        return source.source();
    }

    public List<Map<String, Object>> sandbox() {
        return source.describe();
    }

    /** 本域历史拒绝次数（查询失败按 0，绝不因观测故障加分）。 */
    public int rejectCount(String ip) {
        try {
            Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM risk_decision_log WHERE ip=? AND passed=?",
                    Integer.class, ip, false);
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.warn("IP 历史风险聚合失败（按 0 处理）: {}", e.getMessage());
            return 0;
        }
    }

    /** 画像判定（供既有 IP 因子消费）。 */
    public record Judge(int score, List<String> signals, String detail) {
        String tagsJson() {
            return "[" + String.join(",", signals.stream().map(s -> "\"" + s + "\"").toList()) + "]";
        }
    }

    public Judge judge(boolean proxy, boolean idc, int accounts, int rejects) {
        List<String> signals = new ArrayList<>();
        List<String> why = new ArrayList<>();
        if (proxy) {
            signals.add("PROXY");
            why.add("沙箱表判定为代理/匿名出口");
        }
        if (accounts >= multiAccountThreshold()) {
            signals.add("MULTI_ACCOUNT");
            why.add("同 IP 关联账户 " + accounts + " ≥ " + multiAccountThreshold());
        }
        if (rejects >= rejectThreshold()) {
            signals.add("HISTORY_RISK");
            why.add("历史拒绝 " + rejects + " 次 ≥ " + rejectThreshold());
        }
        if (idc) {
            // IDC 只作画像标签，不单独构成风险（避免云上网段误杀）
            signals.add("IDC");
        }
        int riskySignals = (int) signals.stream().filter(s -> !"IDC".equals(s)).count();
        int score = Math.min(RiskPlusKeys.PROFILE_RISK_SCORE, riskySignals * 40);
        return new Judge(score, signals, riskySignals == 0
                ? "IP 画像正常（账户数 " + accounts + "，历史拒绝 " + rejects + (idc ? "，IDC 网段" : "") + "）"
                : String.join("；", why));
    }

    /** 旁路观测（ip 为空直接跳过；ride/smoke 真实支付即此路径）。 */
    public void observe(RiskContext ctx, RiskResultDTO result) {
        if (!enabled() || ctx.getIp().isBlank()) {
            return;
        }
        String ip = ctx.getIp();
        SandboxIpDataSource.Entry e = source.resolve(ip);
        int accounts = store.countIpAccounts(ip);
        // 历史拒绝增量累计（避免受理链路全表扫描；运营查询 profile() 走权威聚合）
        IpProfileDTO stored = store.getIp(ip);
        int rejects = (stored == null || stored.getRejectCount() == null ? 0 : stored.getRejectCount())
                + (result != null && Boolean.FALSE.equals(result.getPass()) ? 1 : 0);
        Judge j = judge(e.proxy(), e.idc(), accounts, rejects);
        IpProfileDTO d = new IpProfileDTO();
        d.setIp(ip);
        d.setRegion(e.region());
        d.setCountry(e.country());
        d.setProxy(e.proxy());
        d.setIdc(e.idc());
        d.setAccountCount(accounts);
        d.setRejectCount(rejects);
        d.setRiskScore(j.score());
        d.setTags(j.tagsJson());
        d.setDetail(j.detail());
        d.setSource(source.source());
        d.setRetentionUntil(ctx.getNow() + retentionDays() * 86_400_000L);
        store.saveIp(d);
    }

    /** 把画像观测写入 ctx，供既有 IP 因子与 RiskFunctions.ipRisk 使用。 */
    public void loadInto(RiskContext ctx) {
        if (!enabled() || !scoreEnabled() || ctx.getIp().isBlank()) {
            return;
        }
        String ip = ctx.getIp();
        SandboxIpDataSource.Entry e = source.resolve(ip);
        IpProfileDTO stored = store.getIp(ip);
        int accounts = stored != null && stored.getAccountCount() != null
                ? stored.getAccountCount() : store.countIpAccounts(ip);
        int rejects = stored != null && stored.getRejectCount() != null ? stored.getRejectCount() : rejectCount(ip);
        Judge j = judge(e.proxy(), e.idc(), accounts, rejects);
        ctx.setIpProfileLoaded(true);
        ctx.setIpAccountCount(accounts);
        ctx.setIpRejectCount(rejects);
        ctx.setIpProxy(e.proxy());
        ctx.setIpIdc(e.idc());
        ctx.setIpCountry(e.country());
        ctx.setIpRegion(e.region());
        ctx.setIpProfileRisk(j.score() > 0);
        ctx.setIpProfileDetail(j.detail());
    }

    /** 运营查询：实时合并沙箱表 + 本域历史聚合（不依赖是否已采集）。 */
    public IpProfileDTO profile(String ip) {
        SandboxIpDataSource.Entry e = source.resolve(ip);
        IpProfileDTO stored = store.getIp(ip);
        IpProfileDTO d = new IpProfileDTO();
        d.setIp(ip);
        d.setRegion(e.region());
        d.setCountry(e.country());
        d.setProxy(e.proxy());
        d.setIdc(e.idc());
        d.setSource(source.source());
        int accounts = stored != null && stored.getAccountCount() != null
                ? stored.getAccountCount() : store.countIpAccounts(ip);
        int rejects = rejectCount(ip);
        Judge j = judge(e.proxy(), e.idc(), accounts, rejects);
        d.setAccountCount(accounts);
        d.setRejectCount(rejects);
        d.setRiskScore(j.score());
        d.setRisky(j.score() > 0);
        d.setTags(j.tagsJson());
        d.setDetail(j.detail());
        if (stored != null) {
            d.setFirstSeen(stored.getFirstSeen());
            d.setLastSeen(stored.getLastSeen());
            d.setRetentionUntil(stored.getRetentionUntil());
            d.setExpired(stored.getExpired());
        } else {
            d.setExpired(false);
        }
        return d;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ipProfileScoreEnabled", scoreEnabled());
        m.put("ipMultiAccountThreshold", multiAccountThreshold());
        m.put("ipHistoryRejectThreshold", rejectThreshold());
        m.put("ipRetentionDays", retentionDays());
        m.put("ipDataSource", source.source());
        m.put("ipExternalApi", false);
        m.put("ipCount", store.ipCount());
        return m;
    }
}