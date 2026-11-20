package com.qoder.payment.gateway.openplatform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * G-4a 应用策略缓存（网关侧，短 TTL 5s + 撤销/改策略主动失效 → 授权变更即时生效）。
 *
 * <p><b>fail-open 红线</b>：facade 不可用/超时/异常一律回落 {@link AppPolicyDTO#legacy}（全放行），
 * 开放平台治理面故障绝不阻断 ride-pro 正常资金流量。
 *
 * <p>与 {@code MerchantCache}（凭证 60s）分工：本缓存只存授权/配额/计费策略，
 * 撤销时两者一并 evict，避免 60s 内旧凭证继续可用。
 */
@Slf4j
@Component
public class AppPolicyCache {

    private final Cache<String, AppPolicyDTO> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(20_000)
            .build();

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    public AppPolicyDTO resolve(String appId) {
        if (appId == null || appId.isBlank()) {
            return AppPolicyDTO.legacy(appId);
        }
        AppPolicyDTO cached = cache.getIfPresent(appId);
        if (cached != null) {
            return cached;
        }
        AppPolicyDTO policy;
        try {
            policy = openPlatformFacade.resolvePolicy(appId).orThrow();
        } catch (Exception | LinkageError t) {
            log.warn("开放平台策略解析失败，fail-open 放行 appId={}: {}", appId, t.toString());
            return AppPolicyDTO.legacy(appId);
        }
        if (policy == null) {
            policy = AppPolicyDTO.legacy(appId);
        }
        cache.put(appId, policy);
        return policy;
    }

    /** 撤销/改策略后主动失效（同进程即时生效；多实例靠 5s TTL 收敛）。 */
    public void evict(String appId) {
        if (appId != null) {
            cache.invalidate(appId);
        }
    }

    public void evictAll() {
        cache.invalidateAll();
    }
}