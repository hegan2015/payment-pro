package com.qoder.payment.risk.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R-2 Redis 限额计数（docker）：HASH + Lua 原子「校验 + HINCRBY + PEXPIRE」，
 * 跨实例共享、重启不丢，并发不超扣。key = {@code pp:risk:quota:<ruleId>:<维度取值>:<period>}。
 *
 * <p><b>降级</b>：Redis 异常 → 委托 {@link InMemoryQuotaCounter} 继续按限额判定（不静默放行），
 * 并 warn； ride 正常流量在「未配置限额规则」时根本不触达本类（§0.7 红线）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.risk.redis", name = "enabled", havingValue = "true")
public class RedisQuotaCounter implements QuotaCounter {

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(
            "local amt = tonumber(redis.call('HGET', KEYS[1], 'amount') or '0') "
            + "local cnt = tonumber(redis.call('HGET', KEYS[1], 'count') or '0') "
            + "local add = tonumber(ARGV[1]) local la = tonumber(ARGV[2]) local lc = tonumber(ARGV[3]) "
            + "if la > 0 and amt + add > la then return {0, amt, cnt} end "
            + "if lc > 0 and cnt + 1 > lc then return {0, amt, cnt} end "
            + "amt = redis.call('HINCRBY', KEYS[1], 'amount', add) "
            + "cnt = redis.call('HINCRBY', KEYS[1], 'count', 1) "
            + "local ttl = tonumber(ARGV[4]) if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end "
            + "return {1, amt, cnt}", List.class);

    private final StringRedisTemplate redis;
    private final InMemoryQuotaCounter fallback = new InMemoryQuotaCounter();
    private volatile long degradeCount;

    public RedisQuotaCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String quotaKey, String periodKey) {
        return "pp:risk:quota:" + quotaKey + ":" + periodKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public long[] tryConsume(String quotaKey, String periodKey, long amount, long limitAmount,
                             int limitCount, long ttlMillis) {
        try {
            List<Long> r = redis.execute(SCRIPT, List.of(key(quotaKey, periodKey)),
                    String.valueOf(amount), String.valueOf(limitAmount), String.valueOf(limitCount),
                    String.valueOf(ttlMillis));
            if (r != null && r.size() >= 3) {
                return new long[]{r.get(0), r.get(1), r.get(2)};
            }
            log.warn("限额 Lua 返回异常，降级内存计数 key={}", quotaKey);
        } catch (Exception e) {
            degradeCount++;
            log.warn("限额 Redis 不可用，降级内存计数（仍按限额判定，不静默放行）key={}: {}", quotaKey, e.getMessage());
        }
        return fallback.tryConsume(quotaKey, periodKey, amount, limitAmount, limitCount, ttlMillis);
    }

    @Override
    public long[] peek(String quotaKey, String periodKey) {
        try {
            var entries = redis.<String, String>opsForHash().entries(key(quotaKey, periodKey));
            long amt = entries.get("amount") == null ? 0 : Long.parseLong(entries.get("amount"));
            long cnt = entries.get("count") == null ? 0 : Long.parseLong(entries.get("count"));
            return new long[]{amt, cnt};
        } catch (Exception e) {
            log.warn("限额 Redis 读取失败，降级内存: {}", e.getMessage());
            return fallback.peek(quotaKey, periodKey);
        }
    }

    @Override
    public String provider() {
        return "redis";
    }

    public long degradeCount() {
        return degradeCount;
    }
}