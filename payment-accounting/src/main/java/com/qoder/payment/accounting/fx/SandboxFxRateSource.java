package com.qoder.payment.accounting.fx;

import com.qoder.payment.common.config.DynamicConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A-4 沙箱汇率来源（默认实现，local/docker 同构、零外部依赖）。
 *
 * <p>配置经 {@link DynamicConfig}（local 文件 / docker Apollo），改配置不重启生效：
 * <pre>
 * accounting.fx.source            = sandbox          # 发布汇率时的缺省来源标签
 * accounting.fx.sandbox.enabled   = true             # 启动时是否幂等播种沙箱初始版本
 * accounting.fx.currencies        = CNY,USD          # 币种账套目录
 * accounting.fx.pairs             = CNY:USD,USD:CNY  # 启动播种的币对
 * accounting.fx.sandbox.CNY.USD.mid   = 1404         # 1 CNY = 0.1404 USD
 * accounting.fx.sandbox.CNY.USD.buy   = 1390         # 平台买入 CNY 价（客户卖出 CNY 适用）
 * accounting.fx.sandbox.CNY.USD.sell  = 1418
 * accounting.fx.sandbox.CNY.USD.scale = 10000
 * accounting.fx.fee-bps           = 0                # 换汇手续费基点（默认 0，口径清晰可配）
 * </pre>
 *
 * <p><b>红线</b>：沙箱只提供「初始版本」，已发布/已入账的汇率快照一律以 fx_rate 落库真值为准，
 * 配置热更绝不回写历史版本（汇率变动不影响已入账）。
 */
@Component
@RequiredArgsConstructor
public class SandboxFxRateSource implements FxRateSource {

    public static final String KEY_SOURCE = "accounting.fx.source";
    public static final String KEY_SANDBOX_ENABLED = "accounting.fx.sandbox.enabled";
    public static final String KEY_CURRENCIES = "accounting.fx.currencies";
    public static final String KEY_PAIRS = "accounting.fx.pairs";
    public static final String KEY_FEE_BPS = "accounting.fx.fee-bps";

    private final DynamicConfig dynamicConfig;

    @Override
    public String name() {
        return dynamicConfig.get(KEY_SOURCE, "sandbox");
    }

    @Override
    public long[] quote(String fromCurrency, String toCurrency) {
        String prefix = "accounting.fx.sandbox." + fromCurrency + "." + toCurrency + ".";
        int scale = dynamicConfig.getInt(prefix + "scale", FxMath.DEFAULT_SCALE);
        long mid = dynamicConfig.getLong(prefix + "mid", defaultMid(fromCurrency, toCurrency, scale));
        if (mid <= 0) {
            return null;
        }
        // 缺省点差 ±1%（买入价低、卖出价高），可经配置覆盖
        long buy = dynamicConfig.getLong(prefix + "buy", mid - Math.max(1, mid / 100));
        long sell = dynamicConfig.getLong(prefix + "sell", mid + Math.max(1, mid / 100));
        if (buy <= 0 || sell < mid || buy > mid) {
            return null;
        }
        return new long[]{buy, sell, mid, scale};
    }

    /** 内置缺省中间价（仅 CNY/USD 双向；其余币对必须显式配置，绝不臆造汇率）。 */
    private static long defaultMid(String from, String to, int scale) {
        if ("CNY".equals(from) && "USD".equals(to)) {
            return 1404L * scale / FxMath.DEFAULT_SCALE;
        }
        if ("USD".equals(from) && "CNY".equals(to)) {
            return 71225L * scale / FxMath.DEFAULT_SCALE;
        }
        return 0L;
    }
}