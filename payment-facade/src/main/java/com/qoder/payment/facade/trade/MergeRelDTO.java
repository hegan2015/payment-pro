package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** T-5 合并单子订单关联行：amount 为入合并时订单额快照，fee 为支付受理时分摊快照（末行吸收余差）。 */
@Data
public class MergeRelDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String mergeId;
    private String orderId;
    private Long amount;
    private Long fee;
    /** 子订单实时状态（装配时回查）。 */
    private String orderStatus;
    private Long refundedAmount;
}