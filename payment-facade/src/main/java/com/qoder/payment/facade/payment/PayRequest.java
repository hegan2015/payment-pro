package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String method;
    private String accountId;
    private String cardNo;
    private String requestId;
    private String callbackUrl;
    /**
     * P-2 组合支付腿（additive，可空）：非空时走组合编排（Σ腿=订单额、每腿独立 TCC 分支、
     * 一腿失败整体回滚）；缺省单腿路径与改造前逐字节一致（ride BALANCE 同步红线不变）。
     */
    private List<PayLeg> legs;

    /**
     * P-7 营销抵扣诉求（additive，可空）：<b>缺省 null 时编排完全短路</b>——
     * 纯内存判定，零 Dubbo 调用、零额外延迟、fee 与 BALANCE 同步语义逐字节不变（ride §0.7 红线）。
     * 非空时按 券→红包→积分→活动 编排核销（每步幂等 + 失败逆序退回），
     * 补贴入付款 BALANCE 户后资金腿按订单全额扣款 ⇒ 用户净实付 = 订单额 − Σ抵扣。
     */
    private PayMarketing marketing;
}