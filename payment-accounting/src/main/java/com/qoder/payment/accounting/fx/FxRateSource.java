package com.qoder.payment.accounting.fx;

/**
 * A-4 汇率来源 SPI（§0.6 跨域基础设施抽象，双 profile）。
 *
 * <p>local：{@link SandboxFxRateSource}（DynamicConfig 文件配置，零依赖）；
 * docker：同一沙箱实现读 Apollo 配置即可，外部汇率服务可另加实现（{@code @ConditionalOnProperty} 开关），
 * <b>不新增中间件强依赖</b>。来源不可达 → 回落已落库的最新生效版本，绝不阻断换汇（资金可用性优先）。
 */
public interface FxRateSource {

    /** 来源标识（落 fx_rate.source，如 sandbox / apollo / external）。 */
    String name();

    /**
     * 报价：返回 {@code {buyRate, sellRate, midRate, scale}}；无报价返回 null。
     * 全部 long + 整数标度，杜绝浮点。
     */
    long[] quote(String fromCurrency, String toCurrency);
}