package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a 发券请求。<b>发放幂等键</b>：{@code (COUPON_ISSUE, bizNo)} + {@code coupon.request_id} UNIQUE，
 * requestId 缺省由 bizNo 确定性派生（{@code CI:bizNo}），重复发放返回既有券。
 */
@Data
public class CouponIssueRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    /** 可选：按模板发放（面额/门槛/有效期取模板快照）。 */
    private String templateId;
    /** 面额（分）；templateId 为空时必填。 */
    private Long amount;
    /** 门槛（分），缺省 0。 */
    private Long threshold;
    /** 绝对过期时间；缺省用 validDays 或模板。 */
    private Long expireAt;
    private Integer validDays;
    /** 发放幂等键（必填，调用方保证唯一）。 */
    private String bizNo;
    private String operator;
}