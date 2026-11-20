package com.qoder.payment.gateway.openplatform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * G-4a 按 appId 限流与配额守卫（<b>只对已注册应用生效</b>）。
 *
 * <p>分层：① I-7 Sentinel URL 级 + appId 热点参数限流仍在既有 {@code OpenApiController#verify} 内（未改一个字节）；
 * ② 本守卫补<b>应用自定义</b> QPS（rate_limit）与<b>日配额</b>（daily_quota）两个维度，
 * 拒绝一律发生在业务受理之前（不落 PROCESSING 悬挂单）。
 *
 * <p>双 profile：docker Redis INCR（多实例共享，键 pp:openapi:qps|quota:*）；
 * local 零依赖 Caffeine 计数。<b>fail-open</b>：Redis 故障回落本地计数，计数组件异常一律放行。
 *
 * <p><b>ride 兼容红线</b>：未注册 appId（appM1）根本不调用本守卫；注册应用缺省 rateLimit=0/dailyQuota=0
 * 亦表示不限，故默认充足、不误拦。
 */
@Slf4j
@Component
public class ApiQuotaGuard {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String QPS_PREFIX = "pp:openapi:qps:";
    private static final String QUOTA_PREFIX = "pp:openapi:quota:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;

    private final Cache<String, AtomicLong> qpsLocal = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2)).maximumSize(50_000).build();
    private final Cache<String, AtomicLong> quotaLocal = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(26)).maximumSize(50_000).build();

    public ApiQuotaGuard(ObjectProvider<StringRedisTemplate> redisProvider,
                         @Value("${payment.cache.redis.enabled:false}") boolean redisEnabled) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
    }

    /** 放行判定（含计数递增）。reason: null=放行 / QPS / QUOTA。 */
    public Decision tryAcquire(String appId, AppPolicyDTO policy) {
        if (policy == null || !policy.isRegistered()) {
            return Decision.pass();
        }
        int rateLimit = policy.getRateLimit() == null ? 0 : policy.getRateLimit();
        long dailyQuota = policy.getDailyQuota() == null ? 0L : policy.getDailyQuota();
        try {
            if (rateLimit > 0) {
                long second = System.currentTimeMillis() / 1000;
                long used = incr(QPS_PREFIX + appId + ":" + second, Duration.ofSeconds(2), qpsLocal);
                if (used > rateLimit) {
                    MetricsSupport.count("openplatform_quota_reject", "type", "qps");
                    return Decision.deny("QPS", "应用 QPS 超限（" + rateLimit + "/s）");
                }
            }
            if (dailyQuota > 0) {
                String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE);
                long used = incr(QUOTA_PREFIX + appId + ":" + date, Duration.ofHours(26), quotaLocal);
                if (used > dailyQuota) {
                    MetricsSupport.count("openplatform_quota_reject", "type", "daily");
                    return Decision.deny("QUOTA", "应用日调用配额已用尽（" + dailyQuota + "/日）");
                }
            }
        } catch (Exception | LinkageError t) {
            log.warn("开放平台配额守卫故障，fail-open 放行 appId={}: {}", appId, t.toString());
            return Decision.pass();
        }
        return Decision.pass();
    }

    /** 当前秒窗口已用次数（只读，供 /api/openplatform/quota 回显）。 */
    public int qpsUsed(String appId) {
        String key = QPS_PREFIX + appId + ":" + System.currentTimeMillis() / 1000;
        try {
            StringRedisTemplate redis = redis();
            if (redis != null) {
                String value = redis.opsForValue().get(key);
                return value == null ? 0 : Integer.parseInt(value);
            }
        } catch (Exception e) {
            log.debug("配额 QPS 读取失败 appId={}: {}", appId, e.toString());
        }
        AtomicLong local = qpsLocal.getIfPresent(key);
        return local == null ? 0 : local.intValue();
    }

    /** 当日配额已用次数（只读）。 */
    public long quotaUsed(String appId) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE);
        String key = QUOTA_PREFIX + appId + ":" + date;
        try {
            StringRedisTemplate redis = redis();
            if (redis != null) {
                String value = redis.opsForValue().get(key);
                return value == null ? 0L : Long.parseLong(value);
            }
        } catch (Exception e) {
            log.debug("配额日用量读取失败 appId={}: {}", appId, e.toString());
        }
        AtomicLong local = quotaLocal.getIfPresent(key);
        return local == null ? 0L : local.get();
    }

    private long incr(String key, Duration ttl, Cache<String, AtomicLong> localCache) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                Long value = redis.opsForValue().increment(key);
                if (value != null) {
                    if (value == 1L) {
                        redis.expire(key, ttl);
                    }
                    return value;
                }
            } catch (Exception e) {
                log.warn("配额 Redis 计数失败，回落内存 key={}: {}", key, e.toString());
            }
        }
        return localCache.get(key, k -> new AtomicLong()).incrementAndGet();
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    /** 放行/拒绝判定。 */
    public record Decision(boolean allowed, String type, String message) {

        public static Decision pass() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String type, String message) {
            return new Decision(false, type, message);
        }
    }
}