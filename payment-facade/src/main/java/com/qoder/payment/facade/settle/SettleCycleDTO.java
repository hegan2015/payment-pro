package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-1 商户结算周期配置（T+N）。
 *
 * <p>cycleDays 缺省取 DynamicConfig {@code settle.cycle.default-days}（默认 1，即 T+1）；
 * feeBps 为该商户的费率层（可空，空则回退 fee_rule / 配置默认 60bps）。
 */
@Data
public class SettleCycleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    /** T+N 的 N（自然日）；0=T+0 当日结算。 */
    private Integer cycleDays;
    /** 商户级费率层（可空）。 */
    private Integer feeBps;
    private String status;
    private Long createdAt;
    private Long updatedAt;
}