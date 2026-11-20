package com.qoder.payment.risk.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R-2 进程内存限额计数（local 零依赖；docker Redis 异常时亦作降级靶）。
 * 语义与 Redis 版一致：同一 (key,period) 上「校验+累计」在锁内原子完成，并发不超扣。
 */
@Component
@ConditionalOnProperty(prefix = "payment.risk.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryQuotaCounter implements QuotaCounter {

    private record Usage(long amount, int count, long expireAt) {
    }

    private final Map<String, Usage> usage = new ConcurrentHashMap<>();

    private static String k(String key, String period) {
        return key + "|" + period;
    }

    @Override
    public long[] tryConsume(String quotaKey, String periodKey, long amount, long limitAmount,
                             int limitCount, long ttlMillis) {
        String k = k(quotaKey, periodKey);
        long now = System.currentTimeMillis();
        synchronized (usage) {
            Usage cur = usage.get(k);
            if (cur != null && cur.expireAt() > 0 && cur.expireAt() < now) {
                cur = null;
            }
            long usedAmount = cur == null ? 0 : cur.amount();
            int usedCount = cur == null ? 0 : cur.count();
            if (limitAmount > 0 && usedAmount + amount > limitAmount) {
                return new long[]{0, usedAmount, usedCount};
            }
            if (limitCount > 0 && usedCount + 1 > limitCount) {
                return new long[]{0, usedAmount, usedCount};
            }
            long nextAmount = usedAmount + amount;
            int nextCount = usedCount + 1;
            long expireAt = ttlMillis > 0 ? now + ttlMillis : 0;
            usage.put(k, new Usage(nextAmount, nextCount, expireAt));
            return new long[]{1, nextAmount, nextCount};
        }
    }

    @Override
    public long[] peek(String quotaKey, String periodKey) {
        Usage cur = usage.get(k(quotaKey, periodKey));
        if (cur == null || (cur.expireAt() > 0 && cur.expireAt() < System.currentTimeMillis())) {
            return new long[]{0, 0};
        }
        return new long[]{cur.amount(), cur.count()};
    }

    @Override
    public String provider() {
        return "memory";
    }
}