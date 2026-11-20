package com.qoder.payment.common.job;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * I-3 调度运行时状态（进程内共享单例）：XXL-Job 健康度 + 每作业触发/锁计数 + 最近结果。
 *
 * <p>降级红线的数据面：{@link LocalScheduledRunner} 依据本状态判定本地 @Scheduled 兜底是否接管，
 * 调度中心宕机/未配置任务/长时间未触发 → 自动回切，补偿与对账绝不停摆。
 *
 * <p>计数同时经 {@code MetricsSupport} 暴露为 Prometheus 指标（I-8 告警接线）。
 */
public class JobRuntimeState {

    private final String applicationName;
    private final String lockedBy;

    private volatile boolean xxlEnabled;
    private volatile boolean executorRegistered;
    private volatile boolean adminReachable;
    private volatile long lastAdminProbeAt;
    private volatile String lastProbeError;

    private final Map<String, AtomicLong> remoteTriggers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> localRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> localSuppressed = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lockAcquired = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lockSkipped = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRemoteTriggerAt = new ConcurrentHashMap<>();
    private final Map<String, JobResult> lastResults = new ConcurrentHashMap<>();

    public JobRuntimeState(String applicationName) {
        this.applicationName = applicationName == null ? "app" : applicationName;
        this.lockedBy = buildLockedBy();
    }

    /** 锁持有者标识：host/pid/appname（多实例可区分谁持锁）。 */
    private static String buildLockedBy() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        String pid = jvm.contains("@") ? jvm.substring(0, jvm.indexOf('@')) : jvm;
        return host + "/" + pid;
    }

    public String lockedBy() {
        return lockedBy;
    }

    public String applicationName() {
        return applicationName;
    }

    // ---------- XXL-Job 健康度 ----------

    public void setXxlEnabled(boolean enabled) {
        this.xxlEnabled = enabled;
    }

    public boolean xxlEnabled() {
        return xxlEnabled;
    }

    public void setExecutorRegistered(boolean registered) {
        this.executorRegistered = registered;
    }

    public boolean executorRegistered() {
        return executorRegistered;
    }

    public void setAdminReachable(boolean reachable, String error) {
        this.adminReachable = reachable;
        this.lastAdminProbeAt = System.currentTimeMillis();
        this.lastProbeError = error;
    }

    public boolean adminReachable() {
        return adminReachable;
    }

    /** 执行器已启动且调度中心可达。 */
    public boolean xxlHealthy() {
        return xxlEnabled && executorRegistered && adminReachable;
    }

    // ---------- 每作业计数 ----------

    private static AtomicLong counter(Map<String, AtomicLong> map, String name) {
        return map.computeIfAbsent(name, k -> new AtomicLong());
    }

    public void markRemoteTrigger(String name) {
        counter(remoteTriggers, name).incrementAndGet();
        lastRemoteTriggerAt.put(name, System.currentTimeMillis());
    }

    public void markLocalRun(String name) {
        counter(localRuns, name).incrementAndGet();
    }

    public void markLocalSuppressed(String name) {
        counter(localSuppressed, name).incrementAndGet();
    }

    /**
     * ShedLock 获取结果（true=拿到锁并执行，false=锁被他人持有，本次跳过）。
     * 分片作业的锁名带分片后缀（如 {@code reconRun:0/3}），计数归并到作业基名，
     * 使 /actuator/ppjob 的每作业视图能看到全部分片的锁竞争总量。
     */
    public void markLockOutcome(String name, boolean acquired) {
        counter(acquired ? lockAcquired : lockSkipped, baseName(name)).incrementAndGet();
    }

    /** 锁名 → 作业基名（去掉 ":<shardIndex>/<shardTotal>" 分片后缀）。 */
    private static String baseName(String lockName) {
        int colon = lockName.indexOf(':');
        return colon > 0 ? lockName.substring(0, colon) : lockName;
    }

    public void record(JobResult result) {
        lastResults.put(result.name() + "#" + result.shardIndex() + "/" + result.shardTotal(), result);
    }

    public Long lastRemoteTriggerAt(String name) {
        return lastRemoteTriggerAt.get(name);
    }

    public long remoteTriggerCount(String name) {
        return counter(remoteTriggers, name).get();
    }

    /** 供 /actuator/ppjob 输出的每作业状态快照。 */
    public Map<String, Object> jobSnapshot(String name, boolean localActive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("localActive", localActive);
        m.put("remoteTriggers", counter(remoteTriggers, name).get());
        m.put("localRuns", counter(localRuns, name).get());
        m.put("localSuppressed", counter(localSuppressed, name).get());
        m.put("lockAcquired", counter(lockAcquired, name).get());
        m.put("lockSkipped", counter(lockSkipped, name).get());
        m.put("lastRemoteTriggerAt", lastRemoteTriggerAt.get(name));
        Map<String, Object> lasts = new TreeMap<>();
        lastResults.forEach((k, v) -> {
            if (v.name().equals(name)) {
                lasts.put(k, v);
            }
        });
        m.put("lastResults", lasts);
        return m;
    }

    public Map<String, Object> xxlSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", xxlEnabled);
        m.put("executorRegistered", executorRegistered);
        m.put("adminReachable", adminReachable);
        m.put("healthy", xxlHealthy());
        m.put("lastProbeAt", lastAdminProbeAt);
        m.put("lastProbeError", lastProbeError);
        return m;
    }
}