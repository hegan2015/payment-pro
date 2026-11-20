package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * P-2 组合支付腿（additive）：余额/银行卡多腿组合，Σ腿金额=订单额（受理前强校验）。
 * 每腿独立 TCC 分支（账务 bizNo=legId），一腿 Try 失败整体回滚。
 */
@Data
public class PayLeg implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** BALANCE / CARD。 */
    private String method;
    /** BALANCE 腿付款账户。 */
    private String accountId;
    /** CARD 腿卡号。 */
    private String cardNo;
    /** 腿金额（分），必须为正。 */
    private Long amount;
}