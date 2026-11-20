package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** A-4 汇率版本（fx_rate，<b>不分片</b>：全局市场元数据）。只追加，历史版本永不改写。 */
@Data
public class FxRateDO {

    private String id;
    private String fromCurrency;
    private String toCurrency;
    private Long buyRate;
    private Long sellRate;
    private Long midRate;
    private Integer rateScale;
    private Long version;
    private Long effectiveAt;
    private Long bizDate;
    private String source;
    private String operator;
    private Long createdAt;
}