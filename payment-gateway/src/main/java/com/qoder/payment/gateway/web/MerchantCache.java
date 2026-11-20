package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.tcc.TccContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * OpenAPI 商户凭证读缓存（docker Redis）：appId→MemberDTO，凭证不可变，短 TTL。
 * 缓存关闭/不可用/读写异常一律回源 Dubbo，验签链路不因缓存故障中断。
 */
@Slf4j
@Component
public class MerchantCache {

    private static final long TTL_SECONDS = 60;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean enabled;

    public MerchantCache(ObjectProvider<StringRedisTemplate> redisProvider,
                         @Value("${payment.cache.redis.enabled:false}") boolean enabled) {
        this.redisProvider = redisProvider;
        this.enabled = enabled;
    }

    public MemberDTO get(String appId, Supplier<MemberDTO> loader) {
        if (!enabled) {
            return loader.get();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return loader.get();
        }
        String key = "pp:openapi:app:" + appId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return TccContextUtil.fromJson(cached, MemberDTO.class);
            }
        } catch (Exception e) {
            log.warn("商户缓存读取失败，回源 Dubbo appId={}: {}", appId, e.getMessage());
            return loader.get();
        }
        MemberDTO merchant = loader.get();
        try {
            redis.opsForValue().set(key, TccContextUtil.toJson(merchant), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("商户缓存写入失败 appId={}: {}", appId, e.getMessage());
        }
        return merchant;
    }

    /** 密钥轮换后主动失效（M-1）：避免 60s TTL 内验签用旧密钥/旧脱敏值。 */
    public void evict(String appId) {
        if (!enabled || appId == null) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete("pp:openapi:app:" + appId);
        } catch (Exception e) {
            log.warn("商户缓存失效失败 appId={}: {}", appId, e.getMessage());
        }
    }
}