package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-4 汇率快照（long + scale，杜绝浮点）。
 *
 * <p><b>方向口径</b>：{@code rate} 表示 1 单位 {@code fromCurrency} 可换 {@code rate/scale} 单位
 * {@code toCurrency}（两侧均为最小货币单位「分/cent」，故 minor-unit 比值与主单位比值一致）。
 *
 * <p><b>点差口径</b>（银行视角，站在平台侧）：
 * <ul>
 *   <li>{@code buyRate} 买入价：平台买入 from（客户卖出 from）→ 客户拿到更少 to，buy ≤ mid；</li>
 *   <li>{@code sellRate} 卖出价：平台卖出 from（客户买入 from）→ 客户付出更多 to，sell ≥ mid；</li>
 *   <li>{@code midRate} 中间价：账面折算基准，用于 base_amount 与汇兑损益计量。</li>
 * </ul>
 * 换汇（from→to）客户适用 <b>buyRate</b>；(中间价折算额 − 客户实收额) 即平台汇兑损益，落 SYS_FX（to 币种账套）。
 */
@Data
public class FxRateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String fromCurrency;
    private String toCurrency;
    private Long buyRate;
    private Long sellRate;
    private Long midRate;
    private Integer rateScale;
    /** 版本号（同一币对单调递增，1 起）。 */
    private Long version;
    /** 生效时间（epoch millis）；生效时间之前的换汇取不到本版本。 */
    private Long effectiveAt;
    /** 会计日标签（epoch millis 当日 00:00）。 */
    private Long bizDate;
    /** 来源：sandbox / apollo / external-spi / manual（DynamicConfig 可配）。 */
    private String source;
    private String operator;
    private Long createdAt;
}