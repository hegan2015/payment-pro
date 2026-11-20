package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-2 多维限额规则：维度组合 × 周期 × （金额上限 / 笔数上限）。
 *
 * <p>维度组合 {@link #dimensions} 为逗号分隔的 {@code USER/MERCHANT/CHANNEL/METHOD/CARD}，
 * 如 {@code "USER,METHOD"} = 同一用户同一支付方式独立计数；{@code "USER"} = 用户维度。
 * 周期 {@link #period}：{@code SINGLE}（单笔即时）/{@code DAY}/{@code MONTH}/{@code TOTAL}。
 * {@code limitAmount}（分）与 {@code limitCount}（笔）任一 &gt;0 即生效，两者可同时配置。
 */
@Data
public class QuotaRuleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    /** 维度组合，逗号分隔：USER/MERCHANT/CHANNEL/METHOD/CARD。 */
    private String dimensions;
    /** SINGLE/DAY/MONTH/TOTAL。 */
    private String period;
    /** 金额上限（分）；null 或 &lt;=0 = 不限金额。 */
    private Long limitAmount;
    /** 笔数上限；null 或 &lt;=0 = 不限笔数。 */
    private Integer limitCount;
    /** 限定作用域（可选）：如 method=BALANCE / merchantId=M1；空=全量。 */
    private String scopeKey;
    /** 场景：PAY/ALL 等。 */
    private String scene;
    /** ENABLED/DISABLED。 */
    private String status;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;
}