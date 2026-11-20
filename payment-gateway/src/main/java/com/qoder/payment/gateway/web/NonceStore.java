package com.qoder.payment.gateway.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * I-7 OpenAPI 防重放 nonce 去重存储（可选参数，ride-pro 不传 nonce 行为不变）。
 *
 * <p>双 profile：docker Redis {@code SET pp:openapi:nonce:<appId>:<nonce> NX EX 300}；
 * local 内存 Caffeine（expireAfterWrite 300s，容量上限防 OOM）。TTL 与 timestamp ±300s 窗互补：
 * 窗口内同 appId+nonce 二次使用即重放。
 *
 * <p><b>fail-open 红线</b>：Redis 故障回落本进程 Caffeine（仍去重），任何存储异常不得阻断支付。
 * 仅在验签通过后调用（防伪造请求毒化 nonce 键）。
 */
@Slf4j
@Component
public class NonceStore {

    private static final Duration TTL = Duration.ofSeconds(300);
    private static final String KEY_PREFIX = "pp:openapi:nonce:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final Cache<String, Boolean> local = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(200_000)
            .build();

    public NonceStore(ObjectProvider<StringRedisTemplate> redisProvider,
                      @Value("${payment.cache.redis.enabled:false}") boolean redisEnabled) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    /**
   * 登记 nonce 首次使用。
   *
   * @return true=首次（放行）；false=重放（拒绝）
   */
    public boolean firstUse(String appId, String nonce) {
        String key = KEY_PREFIX + appId + ":" + nonce;
        if (redisEnabled) {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                try {
                    Boolean absent = redis.opsForValue().setIfAbsent(key, "1", TTL);
                    return !Boolean.FALSE.equals(absent);
                } catch (Exception e) {
                    log.warn("nonce Redis 去重失败，回落内存 appId={}: {}", appId, e.getMessage());
                }
            }
        }
        return local.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }
}