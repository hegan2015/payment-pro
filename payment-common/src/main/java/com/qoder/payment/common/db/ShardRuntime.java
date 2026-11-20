package com.qoder.payment.common.db;

import org.apache.shardingsphere.infra.hint.HintManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * I-5a 分片运行态（回滚开关 + 读己之写 hint + 影子复制计数）。
 *
 * <p><b>一键回滚</b>：{@link #setActive(boolean)} 把路由从 ShardingSphere 分片切回原单库DataSource，
 * 无需重启（{@code payment.sharding.enabled=false} 则连 Bean 都不装配，见 {@link ShardingAutoConfiguration}）。
 * 因分片期写操作同时 additive 镜像回原单表（{@link ShardMirror}），切回后查询/分页/汇总结果与切前一致。
 *
 * <p><b>读己之写</b>：{@link #readYourWrite(Supplier)} 用 ShardingSphere 官方 {@link HintManager}
 * 强制本次读走主库（结算登记后立即查、退款查原支付结算单、对账报告回读）。未启用分片时零开销直接执行，
 * 不触碰任何 ShardingSphere 类。
 */
public class ShardRuntime {

    private static final Logger log = LoggerFactory.getLogger(ShardRuntime.class);

    private final String appName;
    private final Map<String, String> tables;
    private final int shardCount;
    private final boolean readwriteSplitting;
    private final boolean mirrorLegacy;

    private volatile boolean active = true;
    private final AtomicLong mirrorRows = new AtomicLong();
    private final AtomicLong mirrorFailures = new AtomicLong();
    private final AtomicLong hintReads = new AtomicLong();
    private volatile Map<String, Object> bootstrapStats = new LinkedHashMap<>();

    public ShardRuntime(String appName, Map<String, String> tables, int shardCount,
                        boolean readwriteSplitting, boolean mirrorLegacy) {
        this.appName = appName;
        this.tables = new LinkedHashMap<>(tables);
        this.shardCount = shardCount;
        this.readwriteSplitting = readwriteSplitting;
        this.mirrorLegacy = mirrorLegacy;
    }

    public boolean isActive() {
        return active;
    }

    /** 回滚开关：false=切回原单库（保留分片表与原表全部数据，不删任何行）。 */
    public synchronized boolean setActive(boolean value) {
        boolean before = active;
        active = value;
        if (before != value) {
            log.warn("I-5a 分片路由切换 {} → {}（{}）：数据零删除，原单表与分片表均保留",
                    before ? "SHARDED" : "SINGLE", value ? "SHARDED" : "SINGLE", appName);
        }
        return before;
    }

    public <T> T readYourWrite(Supplier<T> action) {
        if (!active || !readwriteSplitting) {
            return action.get();
        }
        if (HintManager.isInstantiated()) {
            // 外层已持有 hint（事务内 ShardingSphere 本身即强制走主），不嵌套覆盖
            return action.get();
        }
        hintReads.incrementAndGet();
        try (HintManager hint = HintManager.getInstance()) {
            hint.setWriteRouteOnly();
            return action.get();
        }
    }

    public void readYourWrite(Runnable action) {
        readYourWrite(() -> {
            action.run();
            return null;
        });
    }

    public String appName() {
        return appName;
    }

    public Map<String, String> tables() {
        return tables;
    }

    public int shardCount() {
        return shardCount;
    }

    public boolean readwriteSplitting() {
        return readwriteSplitting;
    }

    public boolean mirrorLegacy() {
        return mirrorLegacy;
    }

    public void mirrorOk() {
        mirrorRows.incrementAndGet();
    }

    public void mirrorFailed() {
        mirrorFailures.incrementAndGet();
    }

    public long mirrorRows() {
        return mirrorRows.get();
    }

    public long mirrorFailures() {
        return mirrorFailures.get();
    }

    public long hintReads() {
        return hintReads.get();
    }

    public void bootstrapStats(Map<String, Object> stats) {
        this.bootstrapStats = stats;
    }

    public Map<String, Object> bootstrapStats() {
        return bootstrapStats;
    }
}