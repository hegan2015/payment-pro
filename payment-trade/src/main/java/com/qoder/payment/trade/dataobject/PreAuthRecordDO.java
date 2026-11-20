package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-2 预授权单（trade 库，单库不分片；order_id UNIQUE ⇒ 一订单一预授权）。 */
@Data
public class PreAuthRecordDO {

    private String id;
    private String orderId;
    private String accountId;
    /** 冻结额（分）。 */
    private Long authAmount;
    /** 已累计完成扣款（分，≤ authAmount）。 */
    private Long completedAmount;
    /** AUTHORIZED/PARTIAL_COMPLETED/COMPLETED/CANCELLED。 */
    private String status;
    /** 当前生效的 A-3 冻结单 id（部分完成后为差额重冻单）。 */
    private String freezeId;
    private String requestId;
    private Long createdAt;
    private Long updatedAt;
}