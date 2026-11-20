package com.qoder.payment.gateway.gray;

import com.qoder.tcc.TccContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * I-9 灰度路由决策 + 共享状态（双 profile）。
 *
 * <p><b>状态外置</b>：docker（{@code payment.cache.redis.enabled=true}）灰度配置存 Redis
 * {@code pp:gray:config}（JSON），<b>所有网关实例共享同一份</b>——任一实例改配置/一键回滚，
 * 其余实例下一个请求即生效（每次决策读一次 Redis，亚毫秒级）。
 * local / Redis 故障 → 本进程内存态（零依赖），此时多实例语义降级为单实例并告警。
 *
 * <p><b>分流规则</b>（优先级从高到低）：
 * <ol>
 *   <li>{@code enabled=false} 或 {@code target} 为空 → 全部 stable（回滚态）；</li>
 *   <li>请求头 {@code X-Gray-Tag: gray} → 强制灰度；{@code X-Gray-Tag: stable} → 强制稳定；</li>
 *   <li>否则按权重：{@code crc32(分流键) % 100 < weight} → 灰度。分流键 = Bearer token（无则客户端 IP），
 *       同一会话稳定落同一侧，避免同一用户在两个版本间跳。</li>
 * </ol>
 *
 * <p>灰度目标为另一个网关实例/版本（{@code target} 如 {@code http://127.0.0.1:8081}），
 * 由 {@link GrayForwardFilter} 原样转发（含 Authorization/HMAC body），目标实例自行完成鉴权与业务。
 */
@Slf4j
@Component
public class GrayRouter {

    public static final String KEY = "pp:gray:config";
    public static final String TAG_HEADER = "X-Gray-Tag";
    public static final String HOP_HEADER = "X-Gray-Hop";
    public static final String DECISION_HEADER = "X-Gray-Decision";
    public static final String INSTANCE_HEADER = "X-Instance-Id";

    /** 灰度状态：enabled/weight(0-100)/tag/target。 */
    public record GrayState(boolean enabled, int weight, String tag, String target) {
        public static GrayState disabled() {
            return new GrayState(false, 0, "gray", "");
        }
    }

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final GrayState defaults;
    private final String instanceId;
    private volatile GrayState memory;

    public GrayRouter(ObjectProvider<StringRedisTemplate> redisProvider,
                      @Value("${payment.cache.redis.enabled:false}") boolean redisEnabled,
                      @Value("${payment.gray.enabled:false}") boolean enabled,
                      @Value("${payment.gray.weight:0}") int weight,
                      @Value("${payment.gray.tag:gray}") String tag,
                      @Value("${payment.gray.target:}") String target,
                      @Value("${payment.instance.id:gateway-${server.port:8080}}") String instanceId) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
        this.defaults = new GrayState(enabled, clamp(weight), tag, target == null ? "" : target.trim());
        this.memory = this.defaults;
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }

    public String stateSource() {
        return redis() != null ? "redis" : "memory";
    }

    /** 读共享状态：Redis 优先（多实例一致），异常/关闭回落本进程内存。 */
    public GrayState state() {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(KEY);
                if (json != null && !json.isBlank()) {
                    // 显式按字段构造（不依赖 Jackson record 反射），跨版本稳定
                    java.util.Map<?, ?> parsed = TccContextUtil.fromJson(json, java.util.Map.class);
                    if (parsed != null) {
                        boolean enabled = Boolean.parseBoolean(String.valueOf(parsed.get("enabled")));
                        int weight = parsed.get("weight") instanceof Number n ? n.intValue() : 0;
                        String tag = parsed.get("tag") == null ? "gray" : parsed.get("tag").toString();
                        String target = parsed.get("target") == null ? "" : parsed.get("target").toString();
                        return new GrayState(enabled, clamp(weight), tag, target);
                    }
                }
            } catch (Exception e) {
                log.warn("灰度配置读 Redis 失败，回落本实例内存态: {}", e.getMessage());
            }
        }
        return memory;
    }

    /** 写共享状态（配置/回滚）：Redis 在位则写 Redis（全实例即时生效），同时更新本进程内存兜底。 */
    public GrayState publish(boolean enabled, Integer weight, String tag, String target) {
        GrayState current = state();
        GrayState next = new GrayState(enabled,
                clamp(weight == null ? current.weight() : weight),
                tag == null || tag.isBlank() ? current.tag() : tag.trim(),
                target == null ? current.target() : target.trim());
        this.memory = next;
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY, TccContextUtil.toJson(next));
            } catch (Exception e) {
                log.warn("灰度配置写 Redis 失败（仅本实例生效，多实例语义降级）: {}", e.getMessage());
            }
        }
        log.info("灰度配置更新 enabled={} weight={} tag={} target={} source={}",
                next.enabled(), next.weight(), next.tag(), next.target(), stateSource());
        return next;
    }

    /** 一键回滚：关闭灰度 + 权重归零（target 保留便于审计/再次发布）。 */
    public GrayState rollback() {
        GrayState current = state();
        return publish(false, 0, current.tag(), current.target());
    }

    /**
   * 决策：true=转发到灰度实例。
   *
   * @param tagHeader 请求头 X-Gray-Tag（可空）
   * @param shardKey  分流键（Bearer token 或客户端 IP）
   */
    public boolean toGray(String tagHeader, String shardKey) {
        GrayState state = state();
        if (!state.enabled() || state.target().isBlank()) {
            return false;
        }
        if (tagHeader != null && !tagHeader.isBlank()) {
            String value = tagHeader.trim();
            if (state.tag().equalsIgnoreCase(value) || "gray".equalsIgnoreCase(value)) {
                return true;
            }
            if ("stable".equalsIgnoreCase(value)) {
                return false;
            }
        }
        if (state.weight() <= 0) {
            return false;
        }
        if (state.weight() >= 100) {
            return true;
        }
        return bucket(shardKey) < state.weight();
    }

    /** 稳定哈希分桶（0-99）：同一分流键恒定落同一桶。 */
    public static int bucket(String shardKey) {
        CRC32 crc = new CRC32();
        crc.update((shardKey == null ? "" : shardKey).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100L);
    }

    private StringRedisTemplate redis() {
        return redisEnabled ? redisProvider.getIfAvailable() : null;
    }

    private static int clamp(int weight) {
        return Math.max(0, Math.min(100, weight));
    }
}