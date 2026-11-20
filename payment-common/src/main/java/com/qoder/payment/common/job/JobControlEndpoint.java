package com.qoder.payment.common.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-3 调度运维端点 {@code /actuator/ppjob}（内嵌 management 端口，与业务 HTTP/Dubbo 错开）。
 *
 * <p>只读：执行器健康度、每作业本地/远端触发与锁计数、shedlock 锁表实时行 —— 供 verify-job.py 与运维排障。
 * <p>写入：{@code POST /actuator/ppjob/{jobName}} 手动触发一次（可带 shardIndex/shardTotal/param），
 * 走与 XXL-Job 完全相同的注册表路径，因此 ShedLock 与幂等护栏一致生效。
 *
 * <p>需在域 {@code management.endpoints.web.exposure.include} 中列出 {@code ppjob} 才对外可见；
 * 该端口不经网关暴露（网关只代理 /api/** 与 /openapi/**），属内网运维面。
 */
@Endpoint(id = "ppjob")
public class JobControlEndpoint {

    private final JobSettings settings;
    private final JobRuntimeState state;
    private final JobHandlerRegistry registry;
    private final ObjectProvider<LocalScheduledRunner> localRunner;
    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    public JobControlEndpoint(JobSettings settings, JobRuntimeState state, JobHandlerRegistry registry,
                              ObjectProvider<LocalScheduledRunner> localRunner,
                              ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.settings = settings;
        this.state = state;
        this.registry = registry;
        this.localRunner = localRunner;
        this.jdbcTemplate = jdbcTemplate;
    }

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("application", state.applicationName());
        out.put("lockedBy", state.lockedBy());
        out.put("handlers", new ArrayList<>(registry.names()));
        Map<String, Object> xxl = new LinkedHashMap<>(state.xxlSnapshot());
        xxl.put("appname", settings.appname());
        xxl.put("adminAddresses", settings.adminAddresses());
        xxl.put("port", settings.executorPort());
        xxl.put("executorIp", settings.executorIp());
        out.put("xxl", xxl);
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("enabled", settings.localEnabled());
        local.put("standbyWhenXxlHealthy", settings.localStandby());
        local.put("stallThresholdMs", settings.stallThresholdMs());
        local.put("runnerPresent", localRunner.getIfAvailable() != null);
        out.put("local", local);
        out.put("shedlock", shedlockSnapshot());
        Map<String, Object> jobs = new LinkedHashMap<>();
        LocalScheduledRunner runner = localRunner.getIfAvailable();
        for (String name : registry.names()) {
            boolean active = runner == null || runner.isLocalActive(name);
            jobs.put(name, state.jobSnapshot(name, active));
        }
        out.put("jobs", jobs);
        return out;
    }

    @WriteOperation
    public Map<String, Object> trigger(@Selector String name, @Nullable Integer shardIndex,
                                       @Nullable Integer shardTotal, @Nullable String param) {
        int idx = shardIndex == null ? 0 : shardIndex;
        int total = shardTotal == null ? 1 : shardTotal;
        JobResult result = registry.invoke(name, idx, total, param, "CONTROL");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", name);
        out.put("shardIndex", result.shardIndex());
        out.put("shardTotal", result.shardTotal());
        out.put("success", result.success());
        out.put("durationMs", result.durationMs());
        out.put("error", result.error());
        out.put("known", registry.contains(name));
        out.put("lockAcquired", state.jobSnapshot(name, true).get("lockAcquired"));
        out.put("lockSkipped", state.jobSnapshot(name, true).get("lockSkipped"));
        return out;
    }

    private Map<String, Object> shedlockSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.shedlockEnabled());
        out.put("table", settings.shedlockTable());
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        if (jdbc == null) {
            out.put("rows", List.of());
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, lock_until, locked_at, locked_by FROM " + settings.shedlockTable() + " ORDER BY name");
            long now = System.currentTimeMillis();
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> r = new LinkedHashMap<>(row);
                Object until = row.get("lock_until");
                r.put("held", until instanceof Number n && n.longValue() > now);
                rendered.add(r);
            }
            out.put("rows", rendered);
            out.put("now", now);
        } catch (Exception e) {
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }
}