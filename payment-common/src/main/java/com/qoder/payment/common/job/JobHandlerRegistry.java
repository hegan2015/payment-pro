package com.qoder.payment.common.job;

import com.qoder.payment.common.metrics.MetricsSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JobHandler 注册表：按名字定位作业并统一执行（埋点 + 留痕 + 异常隔离）。
 *
 * <p>持有的是 Spring 容器里的 Bean 引用（含 AOP 代理），因此 {@code @SchedulerLock} 在
 * 本地 @Scheduled、XXL-Job 触发、控制台手动触发三条路径上一致生效。
 *
 * <p>异常在此吞掉并记 ERROR + 指标：与 Spring 调度 ErrorHandler 行为等价（本轮失败不影响下一轮），
 * 保证调度线程不因单次业务异常终止（资金兜底常驻）。
 */
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();
    private final JobRuntimeState state;

    public JobHandlerRegistry(List<JobHandler> jobHandlers, JobRuntimeState state) {
        this.state = state;
        for (JobHandler handler : jobHandlers) {
            JobHandler previous = handlers.put(handler.name(), handler);
            if (previous != null) {
                throw new IllegalStateException("JobHandler 名重复: " + handler.name());
            }
        }
        log.info("I-3 作业注册表就绪 handlers={}", handlers.keySet());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public boolean contains(String name) {
        return handlers.containsKey(name);
    }

    /** 执行一次作业（分片参数透传）；返回结果留痕，不抛出。 */
    public JobResult invoke(String name, int shardIndex, int shardTotal, String param, String source) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        long start = System.currentTimeMillis();
        JobHandler handler = handlers.get(name);
        if (handler == null) {
            JobResult unknown = new JobResult(name, index, total, false, 0,
                    "未知 JobHandler（本域未注册）: " + name, start, source);
            state.record(unknown);
            return unknown;
        }
        boolean ok = true;
        String error = null;
        try {
            handler.execute(index, total, param);
        } catch (Throwable e) {
            ok = false;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[告警] 作业执行失败 job={} shard={}/{} source={}", name, index, total, source, e);
        }
        long duration = System.currentTimeMillis() - start;
        MetricsSupport.count("job_run", "name", name, "status", ok ? "success" : "fail", "source", source);
        MetricsSupport.recordMillis("job_duration", duration, "name", name);
        JobResult result = new JobResult(name, index, total, ok, duration, error, start, source);
        state.record(result);
        return result;
    }
}