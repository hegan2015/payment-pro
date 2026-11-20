package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I-5b 资金域分片端点公共支撑（payment / accounting / trade 三域 {@code /actuator/ppshard} 共用）。
 *
 * <p><b>切流/回滚护栏（§5.1 严格按序）</b>：{@link #toggle(Boolean)} 在切换路由<b>之前</b>
 * 先把当前权威侧全量同步到另一侧，再逐行逐列影子读比对；<b>差异数非 0 即拒绝切换</b>并原样返回差异样本，
 * 绝不带着不一致切流或回滚。切换成功后把路由状态持久化（{@link ShardRouteState}），重启不丢权威侧。
 *
 * <p>所有操作只新增/同步影子副本，<b>绝不 DELETE / DROP / TRUNCATE 任何业务行</b>；
 * 原单库永久保留（§5.1 步骤⑥「下线单库」不做）。
 */
public class FundShardEndpointSupport {

    private static final Logger log = LoggerFactory.getLogger(FundShardEndpointSupport.class);

    private final String domain;
    private final ShardProbeSupport probe;
    private final ShardRowMirror mirror;
    private final ShardRouteState routeState;
    private final Runnable onEnable;
    private volatile Map<String, Object> bootstrapStats = new LinkedHashMap<>();

    public FundShardEndpointSupport(String domain, ShardProbeSupport probe, ShardRowMirror mirror,
                                    ShardRouteState routeState, Runnable onEnable) {
        this.domain = domain;
        this.probe = probe;
        this.mirror = mirror;
        this.routeState = routeState;
        this.onEnable = onEnable == null ? () -> {
        } : onEnable;
    }

    public void bootstrapStats(Map<String, Object> stats) {
        this.bootstrapStats = stats;
    }

    public boolean configured() {
        return mirror != null;
    }

    public ShardRowMirror mirror() {
        return mirror;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (probe == null || mirror == null) {
            out.put("domain", domain);
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("idMode", "SEQUENCE");
            out.put("message", "未装配分片 Bean（payment.sharding.enabled=false），始终单库 + next_value_for");
            return out;
        }
        out.putAll(probe.status());
        out.putAll(mirror.stats());
        out.put("routeStatePersisted", routeState == null ? null : routeState.read());
        out.put("fundBootstrap", bootstrapStats);
        return out;
    }

    public Map<String, Object> shadowCompare() {
        if (mirror == null) {
            return Map.of("shardingConfigured", false, "diffTotal", 0);
        }
        return mirror.compareAll();
    }

    public Map<String, Object> resync(String direction) {
        if (mirror == null) {
            return Map.of("shardingConfigured", false);
        }
        boolean toLegacy = !"TO_SHARDS".equalsIgnoreCase(direction == null ? "" : direction);
        Map<String, Object> sync = mirror.syncAll(toLegacy);
        Map<String, Object> out = new LinkedHashMap<>(sync);
        out.put("compare", mirror.compareAll());
        return out;
    }

    /** 一键切流 / 一键回滚（差异非 0 即拒绝，资金安全优先）。 */
    public Map<String, Object> toggle(Boolean enabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mirror == null || probe == null) {
            out.put("shardingConfigured", false);
            out.put("shardingActive", false);
            out.put("message", "未装配分片 Bean（payment.sharding.enabled=false），始终单库 + next_value_for");
            return out;
        }
        boolean target = !Boolean.FALSE.equals(enabled);
        boolean before = mirror.runtime().isActive();
        out.put("before", before);
        if (target == before) {
            out.put("shardingActive", before);
            out.put("idMode", before ? "SNOWFLAKE" : "SEQUENCE");
            out.put("changed", false);
            return out;
        }
        // ① 切流前护航：权威侧 → 影子侧全量同步（target=true 时权威侧是原单表，故 TO_SHARDS）
        Map<String, Object> sync = mirror.syncAll(!target);
        out.put("sync", sync);
        // ② 影子读逐行逐列比对：差异即停，绝不带着不一致切换
        Map<String, Object> compare = mirror.compareAll();
        out.put("compare", compare);
        long diff = ((Number) compare.get("diffTotal")).longValue();
        long conflicts = ((Number) sync.get("conflicts")).longValue();
        if (diff != 0 || conflicts != 0) {
            out.put("aborted", true);
            out.put("shardingActive", before);
            out.put("idMode", before ? "SNOWFLAKE" : "SEQUENCE");
            out.put("reason", "影子读比对差异/唯一键冲突非 0，禁止切换（资金安全护栏）：diff=" + diff + " conflicts=" + conflicts);
            log.error("I-5b {} 切换被拒绝 diff={} conflicts={} compare={}", domain, diff, conflicts, compare);
            return out;
        }
        // ③ 切路由 + 持久化权威侧
        mirror.runtime().setActive(target);
        if (routeState != null) {
            routeState.write(target);
        }
        if (target) {
            onEnable.run();
        }
        out.put("changed", true);
        out.put("shardingActive", mirror.runtime().isActive());
        out.put("idMode", mirror.runtime().isActive() ? "SNOWFLAKE" : "SEQUENCE");
        log.warn("I-5b {} 路由切换 {} → {}（双写一致，原单库与分片表全部保留）",
                domain, before ? "SHARDED" : "SINGLE", target ? "SHARDED" : "SINGLE");
        return out;
    }

    public Map<String, Object> idgen(String prefix, Integer count, Integer threads) {
        if (probe == null) {
            return Map.of("shardingConfigured", false);
        }
        return probe.idStress(prefix, count == null ? 50000 : count, threads == null ? 8 : threads);
    }
}