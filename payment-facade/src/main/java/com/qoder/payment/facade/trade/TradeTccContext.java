package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 交易 TCC 上下文：支付预留 / 退款标记共用。 */
@Data
public class TradeTccContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String paymentId;
    private Long amount;
    private String bizNo;

    // ---------- P2 · H 泳道 T-5 additive（既有 PAY/REFUND 分支不传，语义不变） ----------

    /** STAGE_PAY 分支：期序（1 起）。 */
    private Integer stageSeq;
    /** MERGE_PAY 分支：所属合并单 id。 */
    private String mergeId;
}