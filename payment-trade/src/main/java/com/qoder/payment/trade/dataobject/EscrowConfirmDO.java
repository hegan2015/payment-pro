package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-1 担保确认收货留痕（trade 库，单库不分片；一单一次，PK=EC+orderId 确定性幂等）。 */
@Data
public class EscrowConfirmDO {

    /** EC + orderId（确定性：重试同一 bizNo，账务 VoucherGuard 吸收重复过账）。 */
    private String id;
    private String orderId;
    private String paymentId;
    /** 本次打款卖家的净额（分）= 已付 − 已退 − 净手续费。 */
    private Long amount;
    private Long fee;
    /** MANUAL（确认收货）/ AUTO（超时自动确认）。 */
    private String type;
    private String bizNo;
    private String holdAccountId;
    private String payeeAccountId;
    private String operator;
    private Long createdAt;
}