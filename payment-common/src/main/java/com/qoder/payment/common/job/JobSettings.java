package com.qoder.payment.common.job;

import org.springframework.core.env.Environment;

/**
 * I-3 调度配置（双 profile 同构，全部带默认值；local 零依赖不连 XXL-Job）。
 *
 * <p>docker 由 {@code application-docker.yml} 打开 {@code payment.job.xxl.enabled=true}，
 * accessToken 经 {@code ~/.config/payment-pro/docker.env} 的 {@code XXL_JOB_ACCESS_TOKEN} 注入（仓库外）。
 *
 * @param xxlEnabled            是否接入 XXL-Job 调度中心（local 默认 false）
 * @param adminAddresses        调度中心地址（共享栈 claim-shared-xxl-job-admin:8094）
 * @param appname               执行器 AppName（调度中心按此分组路由）
 * @param executorIp            执行器注册 IP（容器内调度中心回调宿主须 host.docker.internal）
 * @param executorPort          执行器内嵌 Netty 端口（各域错开）
 * @param accessToken           调度中心通讯令牌（仓库外注入，不落仓库）
 * @param logPath               执行器日志目录
 * @param logRetentionDays      执行器日志保留天数
 * @param probeIntervalMs       调度中心可达性探测间隔（降级判定用）
 * @param localEnabled          本地 @Scheduled 兜底是否装配（matchIfMissing=true）
 * @param localStandby          XXL-Job 正常驱动时本地兜底是否静默（true=docker 语义「@Scheduled 关闭」）
 * @param stallThresholdMs      某作业超过该时长未收到 XXL 触发即回切本地兜底（资金兜底不停摆）
 * @param shedlockEnabled       是否装配 ShedLock JDBC 锁
 * @param shedlockTable         锁表名
 * @param defaultLockAtMostFor  锁最长持有时间兜底（进程猝死时自动到期）
 */
public record JobSettings(boolean xxlEnabled, String adminAddresses, String appname, String executorIp,
                          int executorPort, String accessToken, String logPath, int logRetentionDays,
                          long probeIntervalMs, boolean localEnabled, boolean localStandby,
                          long stallThresholdMs, boolean shedlockEnabled, String shedlockTable,
                          String defaultLockAtMostFor) {

    public static JobSettings from(Environment env) {
        return new JobSettings(
                Boolean.parseBoolean(env.getProperty("payment.job.xxl.enabled", "false")),
                env.getProperty("payment.job.xxl.admin-addresses", "http://127.0.0.1:8094/xxl-job-admin"),
                env.getProperty("payment.job.xxl.appname", "payment-executor"),
                env.getProperty("payment.job.xxl.executor-ip", ""),
                Integer.parseInt(env.getProperty("payment.job.xxl.port", "9998")),
                env.getProperty("payment.job.xxl.access-token", ""),
                env.getProperty("payment.job.xxl.log-path", "logs/xxl-job"),
                Integer.parseInt(env.getProperty("payment.job.xxl.log-retention-days", "30")),
                Long.parseLong(env.getProperty("payment.job.xxl.probe-interval-ms", "3000")),
                Boolean.parseBoolean(env.getProperty("payment.job.local.enabled", "true")),
                Boolean.parseBoolean(env.getProperty("payment.job.local.standby-when-xxl-healthy", "true")),
                Long.parseLong(env.getProperty("payment.job.local.stall-threshold-ms", "10000")),
                Boolean.parseBoolean(env.getProperty("payment.job.shedlock.enabled", "true")),
                env.getProperty("payment.job.shedlock.table", "shedlock"),
                env.getProperty("payment.job.shedlock.default-lock-at-most-for", "PT5M"));
    }

    /** 探测用的调度中心根地址（取第一个）。 */
    public String probeUrl() {
        String first = adminAddresses == null ? "" : adminAddresses.split(",")[0].trim();
        return first.endsWith("/") ? first : first + "/";
    }
}