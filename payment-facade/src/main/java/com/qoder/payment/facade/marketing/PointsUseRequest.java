package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 积分抵扣请求。积分按兑换率换算成金额（分），资金侧走 accounting 复式记账
 * {@code D SYS_MARKETING / C accountId}；积分侧按批次 FIFO 扣减（先过期先用）。
 * 幂等键 {@code (POINTS_USE, bizNo)}，bizNo 缺省由 requestId/paymentId 派生。
 */
@Data
public class PointsUseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private Long points;
    private String paymentId;
    private String orderId;
    private String accountId;
    private String bizNo;
    private String requestId;
    /** true=从冻结积分中消耗（SETTLE），false/缺省=从可用积分消耗（USE）。 */
    private Boolean fromFrozen;
    private String operator;
}