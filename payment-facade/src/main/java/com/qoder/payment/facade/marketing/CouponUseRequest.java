package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a 券核销请求。<b>核销幂等键</b>：{@code (COUPON_USE, couponId:paymentId)} +
 * {@code coupon_use.request_id} UNIQUE + {@code coupon_use.coupon_id} UNIQUE + 券状态 CAS
 * （AVAILABLE→USED 只成功一次）。
 *
 * <p>资金：核销即营销补贴动账，走 accounting facade 复式记账
 * {@code D SYS_MARKETING / C accountId}（accountId 缺省按 memberId 解析 BALANCE 户）。
 */
@Data
public class CouponUseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String couponId;
    private String paymentId;
    private String orderId;
    private String memberId;
    /** 抵扣入账账户（缺省按 memberId 解析）。 */
    private String accountId;
    /** 订单金额（分），用于门槛校验；缺省取券面额（视为已满足门槛）。 */
    private Long orderAmount;
    /** 本次核销额（分）；缺省券面额，不得超过面额。 */
    private Long amount;
    /** 调用方幂等键；缺省由 couponId:paymentId 确定性派生。 */
    private String requestId;
    private String operator;
}