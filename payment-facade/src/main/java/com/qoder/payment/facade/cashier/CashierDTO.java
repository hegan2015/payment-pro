package com.qoder.payment.facade.cashier;

import com.qoder.payment.facade.trade.OrderDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class CashierDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private OrderDTO order;
    private List<PayMethodDTO> methods;

    // ---------- P1 C-1 additive（收银台汇总；既有字段不变） ----------

    /** 收款商户名（取自订单，供 H5 头部展示）。 */
    private String merchantName;
    /** 优惠抵扣合计（分）；营销未落地时为 0，预留 P-7/P-2。 */
    private Long discount;
    /** 实付金额（分）= 订单金额 - 优惠抵扣。 */
    private Long actualAmount;
    /** 小额免密限额（分，I-2 可配）；amount≤此值可跳过支付验证。 */
    private Long freeVerifyLimit;
    /** 币种，固定 CNY。 */
    private String currency;
}