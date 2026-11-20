package com.qoder.payment.common.job;

/**
 * I-3 分布式调度 SPI：业务域把「可被调度的一件事」实现为本接口，调度载体（XXL-Job / 本地 @Scheduled）
 * 只负责触发，幂等与互斥由 ShedLock + 既有幂等模板保证（调度触发与业务执行解耦）。
 *
 * <p>分片语义：{@code shardTotal=1} 表示单片全量；{@code shardTotal>1} 时各片按
 * {@code shardIndex} 取模处理自己那份数据（对账 RC-5 / 结算 ST-1 分片广播铺垫）。
 *
 * <p>实现类必须是 Spring Bean（经注册表持有的代理调用），锁注解 {@code @SchedulerLock}
 * 才能生效；同一锁名不可嵌套（ShedLock 非重入）。
 */
public interface JobHandler {

    /** 全局唯一作业名，同时作为 XXL-Job JobHandler 名与 ShedLock 锁名基名。 */
    String name();

    /**
     * 执行一次作业。
     *
     * @param shardIndex 当前分片序号（0 起）
     * @param shardTotal 分片总数（>=1）
     * @param param      调度中心下发的任务参数（可为 null）
     */
    void execute(int shardIndex, int shardTotal, String param) throws Exception;
}