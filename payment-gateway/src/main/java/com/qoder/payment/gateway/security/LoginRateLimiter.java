package com.qoder.payment.gateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败限流（S-2）：同一 用户名+IP 窗口内失败 ≥ maxFailures 次 → 拒绝登录（429），成功登录即清零。
 *
 * <p><b>I-9 多实例无状态</b>：docker（{@code payment.cache.redis.enabled=true} 且 Redis 在位）计数外置
 * Redis {@code pp:login:fail:<user>|<ip>}（INCR + 首次失败设 EXPIRE=窗口秒数），
 * 两个网关实例共享同一计数——攻击者换实例打口令不会重置配额。
 * 窗口口径为「首次失败起算的固定窗」，与单实例滑动窗在阈值判定上等价（5 次即拒）。
 *
 * <p><b>local 零依赖</b>：无 Redis 时沿用进程内滑动窗口 Deque（语义与改造前逐字节一致）。
 * Redis 读写异常一律降级进程内计数并告警（此时多实例语义降级为单实例），认证链路不因缓存故障中断。
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "pp:login:fail:";

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final long windowMillis;
    private final long windowSeconds;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;

    public LoginRateLimiter(
            @org.springframework.beans.factory.annotation.Value("${payment.auth.login.max-failures:5}") int maxFailures,
            @org.springframework.beans.factory.annotation.Value("${payment.auth.login.window-seconds:60}") long windowSeconds,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @org.springframework.beans.factory.annotation.Value("${payment.cache.redis.enabled:false}") boolean redisEnabled) {
        this.maxFailures = maxFailures;
        this.windowSeconds = windowSeconds;
        this.windowMillis = windowSeconds * 1000;
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    public boolean blocked(String username, String ip) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(KEY_PREFIX + key(username, ip));
                long count = value == null ? 0L : Long.parseLong(value);
                return count >= maxFailures;
            } catch (Exception e) {
                log.warn("登录限流读 Redis 失败，降级本实例计数（多实例语义暂降级为单实例）: {}", e.getMessage());
            }
        }
        Deque<Long> queue = failures.get(key(username, ip));
        if (queue == null) {
            return false;
        }
        synchronized (queue) {
            evict(queue);
            return queue.size() >= maxFailures;
        }
    }

    public void recordFailure(String username, String ip) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String redisKey = KEY_PREFIX + key(username, ip);
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null && count == 1L) {
                    redis.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("登录限流写 Redis 失败，降级本实例计数: {}", e.getMessage());
            }
        }
        Deque<Long> queue = failures.computeIfAbsent(key(username, ip), k -> new ArrayDeque<>());
        synchronized (queue) {
            evict(queue);
            queue.addLast(System.currentTimeMillis());
        }
        if (failures.size() > 10000) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    evict(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    public void reset(String username, String ip) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.delete(KEY_PREFIX + key(username, ip));
            } catch (Exception e) {
                log.warn("登录限流清零 Redis 失败: {}", e.getMessage());
            }
        }
        failures.remove(key(username, ip));
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    private void evict(Deque<Long> queue) {
        long cutoff = System.currentTimeMillis() - windowMillis;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
            queue.pollFirst();
        }
    }

    private static String key(String username, String ip) {
        return (username == null ? "" : username) + "|" + (ip == null ? "" : ip);
    }
}