package com.qoder.payment.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I-2 动态配置键与代码默认值（单一事实来源，改造点与默认值对照表的落点）。
 *
 * <p><b>红线</b>：默认值必须与改造前 {@code @Value}/常量逐一相同（ride 手续费对账口径不变，§0.7）：
 * <ul>
 *   <li>{@code payment.fee-bps=60}：原 PaymentService/ApiController {@code @Value("${payment.fee-bps:60}")}；</li>
 *   <li>{@code payment.risk.single-limit=10000000}（分，10 万元）：原 RiskService.SINGLE_LIMIT；</li>
 *   <li>{@code payment.risk.window-millis=10000}：原 RiskService.WINDOW_MILLIS；</li>
 *   <li>{@code payment.risk.window-max=5}：原 RiskService.WINDOW_MAX。</li>
 * </ul>
 *
 * <p>Apollo namespace 归属：以上均属 {@code application}（公共费率/开关/风控基线）；
 * {@code risk-rules}（R-1 规则引擎）、{@code channel-route}（CH-3）、{@code settle-fee}（ST-2）预留。
 * 密钥类键（*secret*、*password*、*token* 等）严禁进配置中心明文，走 SecretProvider。
 */
public final class ConfigKeys {

    /** 交易手续费率（基点，1bp=0.01%）；资金单（TOPUP/WITHDRAW）恒 0 不受此键影响。 */
    public static final String PAYMENT_FEE_BPS = "payment.fee-bps";
    public static final int DEFAULT_FEE_BPS = 60;

    /** 风控单笔限额（分）。 */
    public static final String RISK_SINGLE_LIMIT = "payment.risk.single-limit";
    public static final long DEFAULT_RISK_SINGLE_LIMIT = 10_000_000L;

    /** 风控频率滑动窗口（毫秒）。 */
    public static final String RISK_WINDOW_MILLIS = "payment.risk.window-millis";
    public static final long DEFAULT_RISK_WINDOW_MILLIS = 10_000L;

    /** 风控频率窗口内同一付款方最大笔数。 */
    public static final String RISK_WINDOW_MAX = "payment.risk.window-max";
    public static final int DEFAULT_RISK_WINDOW_MAX = 5;

    private ConfigKeys() {
    }

    /** 键 → 代码默认值（控制台展示/默认值一致性断言用）。 */
    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(PAYMENT_FEE_BPS, DEFAULT_FEE_BPS);
        defaults.put(RISK_SINGLE_LIMIT, DEFAULT_RISK_SINGLE_LIMIT);
        defaults.put(RISK_WINDOW_MILLIS, DEFAULT_RISK_WINDOW_MILLIS);
        defaults.put(RISK_WINDOW_MAX, DEFAULT_RISK_WINDOW_MAX);
        return defaults;
    }

    /** 密钥类键判定（控制台脱敏用）：命中即掩码，明文不出网关。 */
    public static boolean sensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("secret") || k.contains("password") || k.contains("passwd")
                || k.contains("token") || k.contains("credential")
                || k.contains("privatekey") || k.contains("private-key")
                || k.contains("accesskey") || k.contains("access-key")
                || k.contains("apikey") || k.contains("api-key")
                || k.contains("signkey") || k.contains("sign-key");
    }
}