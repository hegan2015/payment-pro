package com.qoder.payment.common.job;

/**
 * 一次作业触发的结果留痕（供 /actuator/ppjob 与 verify-job.py 断言）。
 *
 * @param name       作业名
 * @param shardIndex 分片序号
 * @param shardTotal 分片总数
 * @param success    业务执行是否成功（锁未获取也算 success=true，见 lockAcquired）
 * @param durationMs 耗时毫秒
 * @param error      失败原因（成功为 null）
 * @param at         触发时刻（epoch millis）
 * @param source     触发来源：LOCAL / XXL / CONTROL
 */
public record JobResult(String name, int shardIndex, int shardTotal, boolean success,
                        long durationMs, String error, long at, String source) {
}