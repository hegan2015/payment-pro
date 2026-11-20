package com.qoder.payment.risk.service;

/** 频率限流抽象：滑动窗口内是否放行。本地内存实现 / docker Redis 实现可切换。 */
public interface RateLimiter {

    boolean tryAcquire(String payer, long now, long windowMillis, int max);

    /**
     * R-1/R-4 只读观测：窗口内已有笔数（<b>不消耗额度</b>，供 {@code txnCount} 函数与频率评分因子）。
     * 默认 -1 = 实现不支持观测（调用方按「无信息」处理，不加分）。
     */
    default long countInWindow(String payer, long now, long windowMillis) {
        return -1L;
    }
}