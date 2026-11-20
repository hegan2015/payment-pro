package com.qoder.payment.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * S-7 二次认证短时 token 存储（双 profile）：
 * docker（payment.cache.redis.enabled=true 且 Redis 在位）→ Redis SETEX；
 * local / Redis 不可用 → 进程内存降级（零依赖，TTL 语义一致）。
 * token 为不透明随机串，值=操作员标识；过期即失效（校验侧 403）。
 */
@Slf4j
@Component
public class StepUpTokenStore {

    private static final String KEY_PREFIX = "pp:stepup:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final Map<String, MemoryEntry> memory = new ConcurrentHashMap<>();

    public StepUpTokenStore(ObjectProvider<StringRedisTemplate> redisProvider,
                            @Value("${payment.cache.redis.enabled:false}") boolean redisEnabled) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    /** 颁发短时 token（ttlSeconds 后失效）。 */
    public String issue(String operator, long ttlSeconds) {
        String token = UUID.randomUUID().toString().replace("-", "");
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + token, operator, ttlSeconds, TimeUnit.SECONDS);
                return token;
            } catch (Exception e) {
                log.warn("step-up token Redis 写入失败，降级内存: {}", e.getMessage());
            }
        }
        memory.put(token, new MemoryEntry(operator, System.currentTimeMillis() + ttlSeconds * 1000));
        return token;
    }

    /** 校验 token：返回操作员标识；缺失/过期/失效返回 null。 */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                return redis.opsForValue().get(KEY_PREFIX + token);
            } catch (Exception e) {
                log.warn("step-up token Redis 读取失败，降级内存: {}", e.getMessage());
            }
        }
        MemoryEntry entry = memory.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            memory.remove(token);
            return null;
        }
        return entry.operator;
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    private record MemoryEntry(String operator, long expireAt) {
    }
}