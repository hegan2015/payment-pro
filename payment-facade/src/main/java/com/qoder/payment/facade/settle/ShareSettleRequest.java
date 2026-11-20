package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-3 分账结算登记请求（结算侧）。比例/固定额由 P-3 分账编排（payment 域）算好后投递，
 * 结算侧只做「登记 + 复式记账动账 + 净额守恒校验」，不做分账编排。
 *
 * <p>幂等键 {@code (SETTLE_SHARE, shareId:receiverAccountId)}；账务 bizNo 由 (shareId,receiver)
 * 确定性派生，跨重试也不会重复动账。
 */
@Data
public class ShareSettleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** P-3 分账单号（同一支付单的一次分账编排）。 */
    private String shareId;
    /** 原支付单号（用于取结算净额做守恒校验）。 */
    private String paymentId;
    /** 分账出账商户。 */
    private String merchantId;
    /** 出账账户（商户结算账户）。 */
    private String merchantAccountId;
    /** 收款账户。 */
    private String receiverAccountId;
    /** 分账金额（分）。 */
    private Long amount;
    /** 分账手续费（分，可空默认 0）。 */
    private Long fee;
}