package com.qoder.payment.risk.service;

/** 频率限流抽象：滑动窗口内是否放行。本地内存实现 / docker Redis 实现可切换。 */
public interface RateLimiter {

    boolean tryAcquire(String payer, long now, long windowMillis, int max);
}