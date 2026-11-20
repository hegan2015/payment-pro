package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-4 超时关闭留痕（trade 库，单库不分片；order_id UNIQUE ⇒ 一单只关一次）。 */
@Data
public class OrderExpireDO {

    /** OE + orderId（确定性幂等）。 */
    private String id;
    private String orderId;
    private Long closedAt;
    /** TIMEOUT（超时关闭）/ MANUAL（运维手动）。 */
    private String type;
    /** 竞态守卫回滚时刻：关闭后发现已受理在途支付单 → CAS 重开为 CREATED（防悬挂）。 */
    private Long reopenedAt;
    private Long createdAt;
}