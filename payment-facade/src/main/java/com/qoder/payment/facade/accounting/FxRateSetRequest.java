package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-4 汇率发布请求（幂等：同币对 + 同生效时间 + 同三价 + 同标度重复发布返回既有版本）。
 *
 * <p>约束：{@code buyRate ≤ midRate ≤ sellRate}（点差口径），三者均 &gt; 0，{@code rateScale > 0}。
 * {@code version} 可显式指定（缺省 = 该币对当前最大版本 + 1）；{@code effectiveAt} 缺省 = 当前时间。
 * {@code source} 缺省取 DynamicConfig {@code accounting.fx.source}（默认 sandbox）。
 */
@Data
public class FxRateSetRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String fromCurrency;
    private String toCurrency;
    private Long buyRate;
    private Long sellRate;
    private Long midRate;
    private Integer rateScale;
    private Long version;
    private Long effectiveAt;
    private String source;
    private String operator;
    /** 可选显式幂等键；为空按 (from,to,effectiveAt,buy,sell,mid,scale) 派生。 */
    private String requestId;
}