package com.qoder.payment.common.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.IJobHandler;

/**
 * XXL-Job {@link IJobHandler} → 本仓库 {@link JobHandler} SPI 适配器。
 *
 * <p>调度中心只负责触发与分片广播：分片序号/总数/任务参数从 {@link XxlJobHelper} 取出后透传给业务作业，
 * 幂等与互斥仍由 ShedLock + 既有幂等模板保证（调度触发与业务执行解耦）。
 */
public class XxlJobHandlerAdapter extends IJobHandler {

    private final String name;
    private final JobHandlerRegistry registry;
    private final JobRuntimeState state;

    public XxlJobHandlerAdapter(String name, JobHandlerRegistry registry, JobRuntimeState state) {
        this.name = name;
        this.registry = registry;
        this.state = state;
    }

    @Override
    public void execute() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        String param = XxlJobHelper.getJobParam();
        if (shardTotal <= 0) {
            shardTotal = 1;
        }
        if (shardIndex < 0) {
            shardIndex = 0;
        }
        state.markRemoteTrigger(name);
        JobResult result = registry.invoke(name, shardIndex, shardTotal, param, "XXL");
        if (result.success()) {
            XxlJobHelper.handleSuccess("job=" + name + " shard=" + result.shardIndex() + "/" + result.shardTotal()
                    + " cost=" + result.durationMs() + "ms");
        } else {
            XxlJobHelper.handleFail("job=" + name + " 失败: " + result.error());
        }
    }
}