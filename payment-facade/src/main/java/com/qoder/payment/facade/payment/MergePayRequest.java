package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * T-5 合并支付请求：一笔支付付多笔子订单（Σ子订单额=合并额，误差 0）。
 * legs 非空时叠加 P-2 组合支付（多支付腿），Σ腿=合并额；缺省 BALANCE 单腿。
 */
@Data
public class MergePayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String mergeOrderId;
    private String accountId;
    private String requestId;
    private String callbackUrl;
    /** 可选组合腿（与 COMBO 叠加）：非空时 Σ腿=合并额，每腿独立 TCC 分支。 */
    private List<PayLeg> legs;
}