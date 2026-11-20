package com.qoder.payment.risk.quota;

/**
 * R-2 限额计数 SPI：按 (quotaKey, periodKey) 原子「校验 + 累计」。
 *
 * <p>docker Redis Lua 原子（跨实例共享、重启不丢）；local 进程内存（零依赖）。
 * <b>拒绝不消耗额度</b>：超限直接返回 allowed=0 且不累加，避免失败请求把额度吃掉。
 */
public interface QuotaCounter {

    /**
     * 原子校验并累计。
     *
     * @param limitAmount 金额上限（分），&le;0 = 不限
     * @param limitCount  笔数上限，&le;0 = 不限
     * @param ttlMillis   key 过期（0 = 不过期，TOTAL 周期）
     * @return {@code [allowed(0/1), usedAmount, usedCount]}（used 为累计后/拒绝时的当前值）
     */
    long[] tryConsume(String quotaKey, String periodKey, long amount, long limitAmount, int limitCount, long ttlMillis);

    /** 只读累计（评分因子/观测用，不累加）。 */
    long[] peek(String quotaKey, String periodKey);

    String provider();
}