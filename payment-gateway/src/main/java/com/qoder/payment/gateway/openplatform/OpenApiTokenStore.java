package com.qoder.payment.gateway.openplatform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qoder.tcc.TccContextUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G-4a OAuth2 client_credentials access_token 存储（与 HMAC <b>并存</b>：token 管授权，HMAC 管签名）。
 *
 * <p>双 profile：docker Redis（pp:openapi:token:&lt;token&gt; + pp:openapi:apptokens:&lt;appId&gt; 索引，
 * 多实例共享、重启存活）；local 零依赖 Caffeine。Redis 故障回落本地，读写异常一律 fail-open 到本地。
 *
 * <p>撤销即时生效：{@link #revokeByApp} 按 appId 索引批量删除 token（控制台撤销授权时同步调用）。
 */
@Slf4j
@Component
public class OpenApiTokenStore {

    private static final String TOKEN_PREFIX = "pp:openapi:token:";
    private static final String APP_INDEX_PREFIX = "pp:openapi:apptokens:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final long ttlSeconds;

    private final Cache<String, TokenEntry> localTokens;
    private final Map<String, Set<String>> localIndex = new ConcurrentHashMap<>();

    public OpenApiTokenStore(ObjectProvider<StringRedisTemplate> redisProvider,
                             @Value("${payment.cache.redis.enabled:false}") boolean redisEnabled,
                             @Value("${payment.openapi.token.ttl-seconds:3600}") long ttlSeconds) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
        this.ttlSeconds = ttlSeconds;
        this.localTokens = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).maximumSize(100_000).build();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(String appId, java.util.List<String> scopes) {
        String token = UUID.randomUUID().toString().replace("-", "");
        TokenEntry entry = new TokenEntry();
        entry.setAppId(appId);
        entry.setScopes(scopes);
        entry.setIssuedAt(System.currentTimeMillis());
        entry.setExpiresAt(entry.getIssuedAt() + ttlSeconds * 1000);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(TOKEN_PREFIX + token, TccContextUtil.toJson(entry), Duration.ofSeconds(ttlSeconds));
                redis.opsForSet().add(APP_INDEX_PREFIX + appId, token);
                redis.expire(APP_INDEX_PREFIX + appId, Duration.ofSeconds(ttlSeconds * 2));
                return token;
            } catch (Exception e) {
                log.warn("token Redis 写入失败，回落内存 appId={}: {}", appId, e.toString());
            }
        }
        localTokens.put(token, entry);
        localIndex.computeIfAbsent(appId, k -> ConcurrentHashMap.newKeySet()).add(token);
        return token;
    }

    /** 校验 token：无效/过期返回 null（调用方转 401）。 */
    public TokenEntry validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(TOKEN_PREFIX + token);
                if (json != null) {
                    TokenEntry entry = TccContextUtil.fromJson(json, TokenEntry.class);
                    return entry != null && entry.getExpiresAt() > System.currentTimeMillis() ? entry : null;
                }
            } catch (Exception e) {
                log.warn("token Redis 读取失败，回落内存: {}", e.toString());
            }
        }
        TokenEntry entry = localTokens.getIfPresent(token);
        return entry != null && entry.getExpiresAt() > System.currentTimeMillis() ? entry : null;
    }

    /** 按 appId 吊销全部 token（授权撤销即时生效）。 */
    public void revokeByApp(String appId) {
        if (appId == null) {
            return;
        }
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                Set<String> tokens = redis.opsForSet().members(APP_INDEX_PREFIX + appId);
                if (tokens != null && !tokens.isEmpty()) {
                    redis.delete(tokens.stream().map(t -> TOKEN_PREFIX + t).toList());
                }
                redis.delete(APP_INDEX_PREFIX + appId);
            } catch (Exception e) {
                log.warn("token Redis 吊销失败 appId={}: {}", appId, e.toString());
            }
        }
        Set<String> local = localIndex.remove(appId);
        if (local != null) {
            local.forEach(localTokens::invalidate);
        }
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    /** token 载荷（不含任何密钥明文）。 */
    @Data
    public static class TokenEntry {
        private String appId;
        private java.util.List<String> scopes;
        private long issuedAt;
        private long expiresAt;
    }
}