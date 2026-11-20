package com.qoder.payment.risk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Redis 滑动窗口限流（docker 模式）：ZSET 按时间戳记分，Lua 原子裁剪+计数+写入，
 * 跨实例共享、重启不丢。语义与内存版一致：windowMillis 内最多 max 笔。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.risk.redis", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2]) "
            + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end "
            + "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]) "
            + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
            + "return 1", Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(String payer, long now, long windowMillis, int max) {
        String key = "pp:risk:window:" + payer;
        String member = now + ":" + UUID.randomUUID();
        Long allowed = redis.execute(SCRIPT, List.of(key),
                String.valueOf(now), String.valueOf(windowMillis), String.valueOf(max), member);
        return allowed != null && allowed == 1L;
    }

    /** R-1/R-4 只读观测：ZCOUNT 窗口内成员数（不写入、不消耗额度）；异常返回 -1=无信息。 */
    @Override
    public long countInWindow(String payer, long now, long windowMillis) {
        try {
            Long n = redis.opsForZSet().count("pp:risk:window:" + payer, now - windowMillis, Double.MAX_VALUE);
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("限频窗口观测失败（按无信息处理，不加分）payer={}: {}", payer, e.getMessage());
            return -1L;
        }
    }
}