package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * T-5 合并单（多订单一次付）：total = Σ子订单额（误差 0）。
 * 状态机 CREATED → PAYING → PAID；PAYING → CREATED（整体回滚/撤销释放，可重付）；
 * CREATED → DISSOLVED（拆分终态，子订单解绑可个别支付）。
 */
@Data
public class MergeOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    private Long totalAmount;
    /** CREATED / PAYING / PAID */
    private String status;
    private String requestId;
    /** 承载单（trade_order bizType=MERGE）：合并支付单 order_id 指向它，recon/settle/notify 口径不变。 */
    private String carrierOrderId;
    private String paymentId;
    private Integer orderCount;
    private Integer paidCount;
    private Long createdAt;
    private Long paidAt;
    /** 子订单明细（含实时订单状态/已退额与 fee 分摊快照，Σamount=totalAmount、Σfee=支付单 fee）。 */
    private List<MergeRelDTO> rels;
}