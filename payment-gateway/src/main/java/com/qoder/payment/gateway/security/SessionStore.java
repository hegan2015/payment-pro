package com.qoder.payment.gateway.security;

import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.tcc.TccContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 控制台 Bearer 会话存储（S-2）：docker Redis 外置（重启不丢会话）+ 进程内 Map 镜像，
 * local 零依赖纯内存。读取顺序：内存 → Redis（网关重启后从 Redis 恢复）；
 * Redis 读写异常一律降级内存，认证链路不因缓存故障中断。
 */
@Slf4j
@Component
public class SessionStore {

    private static final String KEY_PREFIX = "pp:console:session:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final Map<String, Entry> local = new ConcurrentHashMap<>();

    private record Entry(ConsoleUserDTO user, long expiresAt) {
    }

    public SessionStore(ObjectProvider<StringRedisTemplate> redisProvider,
                        @Value("${payment.auth.session.redis.enabled:false}") boolean redisEnabled) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    public void save(String token, ConsoleUserDTO user, long ttlSeconds) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000;
        purgeIfLarge();
        local.put(token, new Entry(user, expiresAt));
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + token, TccContextUtil.toJson(user), ttlSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("会话写 Redis 失败，降级内存: {}", e.getMessage());
            }
        }
    }

    public ConsoleUserDTO get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // I-9 多实例无状态：Redis 在位时以 Redis 为<b>权威</b>——任一实例登出/过期，
        // 其它实例立即失效（不再先读本进程镜像，避免登出后另一实例仍放行）。
        // 本进程镜像仅作 Redis 故障时的降级读（此时多实例语义降级为单实例，日志明确告警）。
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(KEY_PREFIX + token);
                if (json == null) {
                    local.remove(token);
                    return null;
                }
                ConsoleUserDTO user = TccContextUtil.fromJson(json, ConsoleUserDTO.class);
                Long ttl = redis.getExpire(KEY_PREFIX + token, TimeUnit.SECONDS);
                local.put(token, new Entry(user, System.currentTimeMillis()
                        + Math.max(1, ttl == null ? 60 : ttl) * 1000));
                return user;
            } catch (Exception e) {
                log.warn("会话读 Redis 失败，降级本实例内存镜像（多实例语义暂降级为单实例）: {}", e.getMessage());
            }
        }
        Entry entry = local.get(token);
        if (entry != null) {
            if (entry.expiresAt() > System.currentTimeMillis()) {
                return entry.user();
            }
            local.remove(token);
        }
        return null;
    }

    public void delete(String token) {
        if (token == null) {
            return;
        }
        local.remove(token);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.delete(KEY_PREFIX + token);
            } catch (Exception e) {
                log.warn("会话删 Redis 失败: {}", e.getMessage());
            }
        }
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    private void purgeIfLarge() {
        if (local.size() > 1000) {
            long now = System.currentTimeMillis();
            local.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        }
    }
}