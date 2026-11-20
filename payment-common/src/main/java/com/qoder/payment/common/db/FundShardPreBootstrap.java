package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * I-5b 资金域分片前置初始化通用实现（accounting / trade；payment 另有幂等索引表，见其域内子类）。
 *
 * <p>在 ShardingSphere DataSource 创建<b>之前</b>执行，只依赖原单库连接（避免与路由 DataSource 成环）：
 * <ol>
 *   <li>建 {@code shard_route_state}（单库不分片）并恢复持久化路由 —— 切流后重启不退回默认态，
 *       启动同步方向永远正确（可变行资金安全前置）；</li>
 *   <li>按权威侧全行同步影子副本（只 UPDATE 影子副本 / INSERT 缺行，<b>绝不 DELETE/DROP/TRUNCATE</b>）；</li>
 *   <li>逐行逐列比对，差异数记入运行态供端点与 verify-sharding-fund.py 断言（差异非 0 禁止切流）。</li>
 * </ol>
 */
public class FundShardPreBootstrap implements ShardPreBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FundShardPreBootstrap.class);

    private final ShardRouteState routeState;
    private final List<ShardRowMirror.Spec> specs;
    private final AtomicReference<Map<String, Object>> stats = new AtomicReference<>(new LinkedHashMap<>());

    public FundShardPreBootstrap(ShardRouteState routeState, List<ShardRowMirror.Spec> specs) {
        this.routeState = routeState;
        this.specs = specs;
    }

    public Map<String, Object> stats() {
        return stats.get();
    }

    @Override
    public void preBootstrap(DataSource raw, ShardRuntime runtime) {
        Map<String, Object> out = new LinkedHashMap<>();
        routeState.ensureTable();
        Boolean persisted = routeState.read();
        if (persisted != null) {
            runtime.setActive(persisted);
        } else {
            routeState.write(runtime.isActive());
        }
        out.put("routeActive", runtime.isActive());
        out.put("routeRestoredFrom", persisted == null ? "initial-active" : "shard_route_state");
        // 权威侧 = 当前路由侧：分片态权威侧是分片表（同步回原单表）；单库态权威侧是原单表（同步进分片表）。
        // 方向绝不能反，否则会用较旧的影子副本覆盖较新的业务行（资金安全红线）。
        boolean toLegacy = runtime.isActive();
        Map<String, Object> sync = new LinkedHashMap<>();
        Map<String, Object> compare = new LinkedHashMap<>();
        long diff = 0;
        for (ShardRowMirror.Spec spec : specs) {
            sync.put(spec.table(), ShardRowMirror.syncRaw(raw, spec, runtime.shardCount(), toLegacy));
            Map<String, Object> one = ShardRowMirror.compareRaw(raw, raw, false, spec, runtime.shardCount());
            compare.put(spec.table(), one);
            diff += ((Number) one.get("diffTotal")).longValue();
        }
        out.put("syncDirection", toLegacy ? "TO_LEGACY" : "TO_SHARDS");
        out.put("sync", sync);
        out.put("compare", compare);
        out.put("diffTotal", diff);
        stats.set(out);
        if (diff != 0) {
            log.error("I-5b {} 启动同步后仍有 {} 行差异（禁止切流，需人工核查；原行零删除）: {}",
                    runtime.appName(), diff, compare);
        }
        log.warn("I-5b {} 分片前置初始化完成 route={} 同步方向={} 差异={}", runtime.appName(),
                runtime.isActive() ? "SHARDED" : "SINGLE", out.get("syncDirection"), diff);
    }
}