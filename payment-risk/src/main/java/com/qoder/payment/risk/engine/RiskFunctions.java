package com.qoder.payment.risk.engine;

import com.qoder.payment.risk.quota.QuotaCounter;
import com.qoder.payment.risk.service.RateLimiter;
import lombok.extern.slf4j.Slf4j;

/**
 * R-1 自定义函数体（QLExpress 扩展位，非自研薄循环）。函数无状态，能力经
 * {@link RiskContext} 逐请求注入（ThreadLocal 传递，执行线程内 set/clear）。
 *
 * <p>可用函数：
 * <ul>
 *   <li>{@code txnCount(payerId, windowMillis)} → 窗口内笔数（只读，不消耗限频额度）</li>
 *   <li>{@code quotaUsed(key, periodKey)} → 限额累计金额（分）</li>
 *   <li>{@code quotaCount(key, periodKey)} → 限额累计笔数</li>
 *   <li>{@code inList(listType, valueType, value)} → 名单命中（BLACK/WHITE/GRAY）</li>
 *   <li>{@code deviceRisk(deviceId)} / {@code ipRisk(ip)} → 风险设备/IP 名单</li>
 *   <li>{@code hourOfDay()} → 当前小时（Asia/Shanghai）</li>
 *   <li>{@code amountDeviation()} → 本笔 / 历史均额倍数</li>
 * </ul>
 * 函数内部异常一律吞掉返回「安全值」（0/false），绝不因函数故障让规则误命中。
 */
@Slf4j
public class RiskFunctions {

    private static final ThreadLocal<RiskContext> CURRENT = new ThreadLocal<>();

    static void bind(RiskContext ctx) {
        CURRENT.set(ctx);
    }

    static void unbind() {
        CURRENT.remove();
    }

    private static RiskContext ctx() {
        return CURRENT.get();
    }

    public long txnCount(Object payer, Object windowMillis) {
        RiskContext c = ctx();
        if (c == null || c.getRateLimiter() == null) {
            return 0L;
        }
        try {
            String p = payer == null || String.valueOf(payer).isBlank() ? c.getPayerId() : String.valueOf(payer);
            return c.getRateLimiter().countInWindow(p, c.getNow(), num(windowMillis, 60_000L));
        } catch (Exception e) {
            log.warn("txnCount 函数异常（返回 0，不误命中）: {}", e.getMessage());
            return 0L;
        }
    }

    /** 数值入参统一宽容解析（QLExpress 精确模式可能传 BigDecimal/Integer/Long/String）。 */
    private static long num(Object v, long def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return new java.math.BigDecimal(String.valueOf(v).trim()).longValue();
        } catch (Exception e) {
            return def;
        }
    }

    public long quotaUsed(String key, String periodKey) {
        long[] v = peek(key, periodKey);
        return v[0];
    }

    public long quotaCount(String key, String periodKey) {
        long[] v = peek(key, periodKey);
        return v[1];
    }

    private long[] peek(String key, String periodKey) {
        RiskContext c = ctx();
        if (c == null || c.getQuotaCounter() == null || key == null) {
            return new long[]{0, 0};
        }
        try {
            return c.getQuotaCounter().peek(key, periodKey == null ? "total" : periodKey);
        } catch (Exception e) {
            log.warn("quotaUsed 函数异常（返回 0）: {}", e.getMessage());
            return new long[]{0, 0};
        }
    }

    public boolean inList(String listType, String valueType, String value) {
        RiskContext c = ctx();
        if (c == null || c.getListLookup() == null || value == null || value.isBlank()) {
            return false;
        }
        try {
            return c.getListLookup().test(listType, valueType + ":" + value);
        } catch (Exception e) {
            log.warn("inList 函数异常（返回 false）: {}", e.getMessage());
            return false;
        }
    }

    public boolean deviceRisk(String deviceId) {
        RiskContext c = ctx();
        if (c == null || deviceId == null) {
            return false;
        }
        if (c.getRiskDevices().contains(deviceId)) {
            return true;
        }
        // R-5 扩展：画像判定（同设备多账户 / 指纹漂移 / 行为突发）。
        // 默认 risk.device.profile-score-enabled=false → deviceProfileRisk 恒 false，口径与 P1-B 逐字节一致。
        return matches(c.getDeviceId(), deviceId) && c.isDeviceProfileRisk();
    }

    public boolean ipRisk(String ip) {
        RiskContext c = ctx();
        if (c == null || ip == null) {
            return false;
        }
        if (c.getRiskIps().contains(ip)) {
            return true;
        }
        // R-5 扩展：画像判定（代理 / 同 IP 多账户 / 历史拒绝）。默认影子关闭 → 恒 false。
        return matches(c.getIp(), ip) && c.isIpProfileRisk();
    }

    private static boolean matches(String ctxValue, String arg) {
        return arg == null || arg.isBlank() || arg.equals(ctxValue);
    }

    // ---------- R-5 画像函数（扩展既有因子，不另起评分体系） ----------

    /** 同设备关联账户数（无画像信息返回 0，绝不误命中）。 */
    public long deviceAccountCount(String deviceId) {
        RiskContext c = ctx();
        if (c == null || !matches(c.getDeviceId(), deviceId)) {
            return 0L;
        }
        return c.getDeviceAccountCount();
    }

    /** 指纹是否稳定（UNSTABLE 才返回 false；无信息按稳定处理）。 */
    public boolean deviceStable(String deviceId) {
        RiskContext c = ctx();
        if (c == null || !matches(c.getDeviceId(), deviceId)) {
            return true;
        }
        return !"UNSTABLE".equals(c.getDeviceStability());
    }

    public boolean ipProxy(String ip) {
        RiskContext c = ctx();
        return c != null && matches(c.getIp(), ip) && c.isIpProxy();
    }

    public boolean ipIdc(String ip) {
        RiskContext c = ctx();
        return c != null && matches(c.getIp(), ip) && c.isIpIdc();
    }

    public String ipCountry(String ip) {
        RiskContext c = ctx();
        return c == null || !matches(c.getIp(), ip) ? "" : c.getIpCountry();
    }

    // ---------- R-6 AML 函数（复用同一 QLExpress 引擎与护栏） ----------

    /** 付款方当日累计金额（分）；能力未注入或异常 → 0。 */
    public long dayAmount(Object payerId) {
        RiskContext c = ctx();
        if (c == null || c.getAmlLookup() == null) {
            return 0L;
        }
        try {
            return c.getAmlLookup().dayAmount(payer(c, payerId));
        } catch (Exception e) {
            log.warn("dayAmount 函数异常（返回 0，不误命中）: {}", e.getMessage());
            return 0L;
        }
    }

    /** 付款方当日累计笔数；能力未注入或异常 → 0。 */
    public long dayCount(Object payerId) {
        RiskContext c = ctx();
        if (c == null || c.getAmlLookup() == null) {
            return 0L;
        }
        try {
            return c.getAmlLookup().dayCount(payer(c, payerId));
        } catch (Exception e) {
            log.warn("dayCount 函数异常（返回 0，不误命中）: {}", e.getMessage());
            return 0L;
        }
    }

    /** 制裁名单筛查（沙箱名单）；能力未注入或异常 → false。 */
    public boolean sanctionHit(String subject) {
        return watchlist("SANCTION", subject);
    }

    /** 政要（PEP）名单筛查（沙箱名单）；能力未注入或异常 → false。 */
    public boolean pepHit(String subject) {
        return watchlist("PEP", subject);
    }

    private boolean watchlist(String listType, String subject) {
        RiskContext c = ctx();
        if (c == null || c.getAmlLookup() == null || subject == null || subject.isBlank()) {
            return false;
        }
        try {
            return c.getAmlLookup().watchlistHit(listType, subject);
        } catch (Exception e) {
            log.warn("{} 名单筛查函数异常（返回 false，不误命中）: {}", listType, e.getMessage());
            return false;
        }
    }

    private static String payer(RiskContext c, Object payerId) {
        return payerId == null || String.valueOf(payerId).isBlank() ? c.getPayerId() : String.valueOf(payerId);
    }

    public int hourOfDay() {
        RiskContext c = ctx();
        return c == null ? 0 : c.getHour();
    }

    public double amountDeviation() {
        RiskContext c = ctx();
        return c == null ? 0d : c.getAmountDeviation();
    }

    /** 供 RateLimiter 只读计数用的类型引用（避免未使用 import 告警）。 */
    static Class<?>[] limiterTypes() {
        return new Class<?>[]{RateLimiter.class, QuotaCounter.class};
    }
}