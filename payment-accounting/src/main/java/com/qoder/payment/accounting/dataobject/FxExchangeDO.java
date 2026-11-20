package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/**
 * A-4 换汇单（fx_exchange，<b>不分片</b>：全局幂等单据 + 汇率快照）。
 * 只追加：汇率快照落库后永不改写，后续汇率变动不影响已入账（红线）。
 */
@Data
public class FxExchangeDO {

    private String bizNo;
    private String fromBizNo;
    private String toBizNo;
    private String fromAccountId;
    private String toAccountId;
    private String fromCurrency;
    private String toCurrency;
    private Long amount;
    private Long fee;
    private Integer feeBps;
    private Long grossToAmount;
    private Long netToAmount;
    private Long fxGain;
    private String fxGainAccount;
    private String feeAccount;
    private Long buyRate;
    private Long sellRate;
    private Long midRate;
    private Integer rateScale;
    private Long rateVersion;
    private String rateSource;
    private Long rateEffectiveAt;
    private Long fromBalanceAfter;
    private Long toBalanceAfter;
    private String operator;
    private Long createdAt;
}