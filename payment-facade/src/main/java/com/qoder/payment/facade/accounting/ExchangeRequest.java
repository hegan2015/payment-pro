package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-4 换汇请求：fromAccount（卖出币种户）→ toAccount（买入币种户）。
 *
 * <p><b>幂等键</b>：{@code (EXCHANGE, bizNo)}；两张凭证号确定性派生 {@code bizNo+"-F"} / {@code bizNo+"-T"}，
 * 受 VoucherGuard 只追加护栏保护，重放不重复过账。
 *
 * <p><b>金额口径</b>：{@code amount} 为 fromAccount 币种的最小单位（分/cent）long；
 * {@code feeBps} 为换汇手续费基点（缺省取 DynamicConfig {@code accounting.fx.fee-bps}，默认 0），
 * 手续费在 <b>from 币种</b> 额外收取，入 {@code SYS_FEE}（6001 手续费收入，与支付手续费同一口径），
 * 不新增系统户/科目映射（既有 11 条固定映射与 18 条科目目录不变）。
 */
@Data
public class ExchangeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String fromAccountId;
    private String toAccountId;
    private Long amount;
    /** 幂等键（必填）。 */
    private String bizNo;
    private Integer feeBps;
    /** 指定汇率版本（缺省取生效时间已到的最新版本）——用于「汇率变动不影响已入账」回放。 */
    private Long rateVersion;
    private String subject;
    private String operator;
}