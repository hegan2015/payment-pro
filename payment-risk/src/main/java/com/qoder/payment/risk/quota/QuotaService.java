package com.qoder.payment.risk.quota;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.QuotaRuleDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.risk.config.RiskConfigManager;
import com.qoder.payment.risk.config.RiskKeys;
import com.qoder.payment.risk.engine.RiskContext;
import com.qoder.payment.risk.store.RiskStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * R-2 多维限额校验（单笔 / 日累计 / 月累计 / 笔数 × 用户·商户·渠道·支付方式·卡 组合）。
 *
 * <p><b>受理前拒绝</b>：本服务在 payment 域「insertPayment 之后、TCC begin 之前」被调用，
 * 拒绝即置 FAILED 并抛错，绝不留 PROCESSING 悬挂单（口径与 P0 单笔限额一致）。
 * <p><b>默认零影响</b>：未配置限额规则时直接返回放行，不触达 Redis/DB（ride 正常流量零开销）。
 * <p><b>周期重置</b>：periodKey 含业务日期（Asia/Shanghai），跨日/月自然换 key 重置。
 */
@Slf4j
@Service
public class QuotaService {

    /** 校验结果。 */
    public record Result(boolean pass, String hit, String reason) {
        static Result ok() {
            return new Result(true, null, null);
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final RiskConfigManager configs;
    private final QuotaCounter counter;
    private final RiskStore store;
    private final DynamicConfig config;
    /** 内存降级靶（与 local profile 同款实现）：Redis 故障或显式 force-memory 时启用。 */
    private final QuotaCounter memory = new InMemoryQuotaCounter();

    public QuotaService(RiskConfigManager configs, QuotaCounter counter, RiskStore store, DynamicConfig config) {
        this.configs = configs;
        this.counter = counter;
        this.store = store;
        this.config = config;
    }

    /** 当前生效计数器：force-memory=true 时走内存（等价性验证/降级），否则走 profile 实现。 */
    private QuotaCounter active() {
        return config.getBool(RiskKeys.QUOTA_FORCE_MEMORY, RiskKeys.DEFAULT_QUOTA_FORCE_MEMORY) ? memory : counter;
    }

    public String provider() {
        return active().provider();
    }

    /** 规则引擎 quotaUsed/quotaCount 函数的只读计数入口。 */
    public QuotaCounter counterForFunction() {
        return active();
    }

    public Result check(RiskContext ctx) {
        if (!config.getBool(RiskKeys.QUOTA_ENABLED, RiskKeys.DEFAULT_QUOTA_ENABLED)) {
            return Result.ok();
        }
        List<QuotaRuleDTO> all = configs.quotas();
        if (all.isEmpty()) {
            return Result.ok();
        }
        long now = ctx.getNow();
        for (QuotaRuleDTO q : all) {
            if (!"ENABLED".equals(q.getStatus()) || !sceneMatch(q.getScene(), ctx.getScene())) {
                continue;
            }
            if (!scopeMatch(q.getScopeKey(), ctx)) {
                continue;
            }
            long limitAmount = q.getLimitAmount() == null ? 0 : q.getLimitAmount();
            int limitCount = q.getLimitCount() == null ? 0 : q.getLimitCount();
            if (limitAmount <= 0 && limitCount <= 0) {
                continue;
            }
            String periodKey = periodKey(q.getPeriod(), now);
            if ("SINGLE".equals(q.getPeriod())) {
                if (limitAmount > 0 && ctx.getAmount() > limitAmount) {
                    return reject(q, "单笔金额 " + ctx.getAmount() + " 超出限额 " + limitAmount + " 分");
                }
                continue;
            }
            String quotaKey = quotaKey(q, ctx);
            long[] r = active().tryConsume(quotaKey, periodKey, ctx.getAmount(), limitAmount, limitCount,
                    ttlMillis(q.getPeriod()));
            store.upsertUsage(quotaKey, periodKey, r[1], (int) r[2]);
            if (r[0] == 0) {
                String detail = limitAmount > 0 && r[1] + ctx.getAmount() > limitAmount
                        ? "累计金额 " + r[1] + "+" + ctx.getAmount() + " 超出限额 " + limitAmount + " 分"
                        : "累计笔数 " + r[2] + "+1 超出限额 " + limitCount + " 笔";
                return reject(q, detail);
            }
        }
        return Result.ok();
    }

    private static Result reject(QuotaRuleDTO q, String detail) {
        String hit = q.getId() + "[" + q.getDimensions() + "/" + q.getPeriod() + "]";
        String reason = "超出限额(" + q.getDimensions() + "/" + q.getPeriod() + "): " + detail;
        log.warn("多维限额拒绝 quota={} reason={}", hit, reason);
        return new Result(false, hit, reason);
    }

    /** 限额 key：规则 id + 各维度取值（缺省 "-"），不同规则独立计数。 */
    public static String quotaKey(QuotaRuleDTO q, RiskContext ctx) {
        List<String> parts = new ArrayList<>();
        parts.add(q.getId());
        for (String d : q.getDimensions().split(",")) {
            parts.add(dimensionValue(d.trim(), ctx));
        }
        return String.join(":", parts);
    }

    private static String dimensionValue(String dim, RiskContext ctx) {
        String v = switch (dim) {
            case "USER" -> ctx.getPayerId();
            case "MERCHANT" -> ctx.getMerchantId();
            case "CHANNEL" -> ctx.getChannelCode();
            case "METHOD" -> ctx.getMethod();
            case "CARD" -> ctx.getCardBin();
            default -> "";
        };
        return v == null || v.isBlank() ? "-" : v;
    }

    public static String periodKey(String period, long now) {
        var date = Instant.ofEpochMilli(now).atZone(ZONE);
        return switch (period) {
            case "DAY" -> DAY.format(date);
            case "MONTH" -> MONTH.format(date);
            case "SINGLE" -> "single";
            default -> "total";
        };
    }

    private static long ttlMillis(String period) {
        return switch (period) {
            case "DAY" -> 2 * 86400_000L;
            case "MONTH" -> 62 * 86400_000L;
            default -> 0L;
        };
    }

    private static boolean sceneMatch(String ruleScene, String scene) {
        return ruleScene == null || ruleScene.isBlank() || "ALL".equalsIgnoreCase(ruleScene)
                || ruleScene.equalsIgnoreCase(scene);
    }

    /** scopeKey 形如 {@code method=BALANCE,merchantId=M1}；空=全量。 */
    private static boolean scopeMatch(String scopeKey, RiskContext ctx) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return true;
        }
        for (String pair : scopeKey.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            String actual = switch (k) {
                case "method" -> ctx.getMethod();
                case "merchantId" -> ctx.getMerchantId();
                case "payerId" -> ctx.getPayerId();
                case "channelCode" -> ctx.getChannelCode();
                case "scene" -> ctx.getScene();
                case "cardBin" -> ctx.getCardBin();
                default -> null;
            };
            if (actual == null || !actual.equalsIgnoreCase(v)) {
                return false;
            }
        }
        return true;
    }

    /** 供评分因子读取「用户日累计」用于金额偏离计算（只读）。 */
    public long[] dayUsage(String payerId, long now) {
        return active().peek("SCORE:" + payerId, periodKey("DAY", now));
    }

    /** 评分用日累计的镜像写入（与限额规则计数分离，key 前缀 SCORE；仅显式开启历史累计时调用）。 */
    public void recordScoreUsage(String payerId, long now, long amount) {
        active().tryConsume("SCORE:" + payerId, periodKey("DAY", now), amount, 0, 0, ttlMillis("DAY"));
    }
}