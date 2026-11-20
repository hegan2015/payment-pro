package com.qoder.payment.member.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.facade.member.MemberSessionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * M-5 C 端会话存储（独立于控制台 SessionStore，互不影响）：
 * local 零依赖纯内存；docker Redis 外置（pp:member:session:&lt;token&gt; TTL +
 * pp:member:session:idx:&lt;memberId&gt; 会员→token 索引，改密/冻结全端失效）。
 * Redis 读写异常一律降级内存，登录链路不因缓存故障中断（与网关 S-2 同策略）。
 */
@Slf4j
@Component
public class MemberSessionStore {

    private static final String KEY_PREFIX = "pp:member:session:";
    private static final String IDX_PREFIX = "pp:member:session:idx:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final Map<String, Entry> local = new ConcurrentHashMap<>();

    private record Entry(MemberSessionDTO session, long expiresAt) {
    }

    public MemberSessionStore(ObjectProvider<StringRedisTemplate> redisProvider,
                              @Value("${payment.member.session.redis.enabled:false}") boolean redisEnabled) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    public void save(String token, MemberSessionDTO session, long ttlSeconds) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000;
        session.setExpiresAt(expiresAt);
        purgeIfLarge();
        local.put(token, new Entry(session, expiresAt));
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + token, JSON.writeValueAsString(session), ttlSeconds, TimeUnit.SECONDS);
                redis.opsForSet().add(IDX_PREFIX + session.getMemberId(), token);
                redis.expire(IDX_PREFIX + session.getMemberId(), ttlSeconds * 2, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("会员会话写 Redis 失败，降级内存: {}", e.getMessage());
            }
        }
    }

    public MemberSessionDTO get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Entry entry = local.get(token);
        if (entry != null) {
            if (entry.expiresAt() > System.currentTimeMillis()) {
                return entry.session();
            }
            local.remove(token);
        }
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(KEY_PREFIX + token);
                if (json != null) {
                    MemberSessionDTO session = JSON.readValue(json, MemberSessionDTO.class);
                    Long ttl = redis.getExpire(KEY_PREFIX + token, TimeUnit.SECONDS);
                    local.put(token, new Entry(session, System.currentTimeMillis()
                            + Math.max(1, ttl == null ? 60 : ttl) * 1000));
                    return session;
                }
            } catch (Exception e) {
                log.warn("会员会话读 Redis 失败，降级内存: {}", e.getMessage());
            }
        }
        return null;
    }

    public void delete(String token) {
        if (token == null) {
            return;
        }
        Entry entry = local.remove(token);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.delete(KEY_PREFIX + token);
                if (entry != null) {
                    redis.opsForSet().remove(IDX_PREFIX + entry.session().getMemberId(), token);
                }
            } catch (Exception e) {
                log.warn("会员会话删 Redis 失败: {}", e.getMessage());
            }
        }
    }

    /** 改密/冻结：该会员全部会话立即失效（内存 + Redis 索引）。 */
    public void invalidateByMember(String memberId) {
        if (memberId == null) {
            return;
        }
        local.entrySet().removeIf(e -> memberId.equals(e.getValue().session().getMemberId()));
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                Set<String> tokens = redis.opsForSet().members(IDX_PREFIX + memberId);
                if (tokens != null) {
                    for (String token : tokens) {
                        redis.delete(KEY_PREFIX + token);
                        local.remove(token);
                    }
                }
                redis.delete(IDX_PREFIX + memberId);
            } catch (Exception e) {
                log.warn("会员会话批量失效 Redis 失败: {}", e.getMessage());
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