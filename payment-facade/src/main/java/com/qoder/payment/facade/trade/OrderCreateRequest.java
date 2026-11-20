package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private String subject;
    private Long amount;
    /** 商户外部交易号（可选）：同商户下幂等，重复下单返回已有订单。 */
    private String outTradeNo;
    /** 单据类型：TRADE（商户交易，默认）/ TOPUP（充值）/ WITHDRAW（提现）。 */
    private String bizType;

    // ---------- P1 · G 泳道 T-3/T-4 additive（缺省即改造前行为，既有字段语义一字未改） ----------

    /**
     * T-3 交易产品：INSTANT（即时到账，<b>缺省</b>）/ ESCROW（担保交易）/ PREAUTH（预授权）。
     * 为空/空白一律按 INSTANT 处理 —— ride {@code trade.create} 不传本字段，路径逐字节不变。
     */
    private String productType;

    /**
     * T-4 未支付超时关闭分钟数（可选）：缺省取 DynamicConfig {@code trade.order.expire-minutes}（默认 30）。
     * 只写 {@code trade_order.expire_at} deadline，<b>不改变受理/支付语义</b>；
     * 自动关闭作业默认不跑批（由 XXL/控制台/ppjob 触发）。
     */
    private Integer expireMinutes;
}