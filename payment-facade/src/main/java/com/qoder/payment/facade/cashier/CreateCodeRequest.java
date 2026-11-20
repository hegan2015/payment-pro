package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** C-2 生成付款码/收款码请求（沙箱）。 */
@Data
public class CreateCodeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** USER_PRESENTED（被扫，用户出示）/ MERCHANT_PRESENTED（主扫，商户出码）。 */
    private String type;
    /** 被扫=付款账户；主扫=收款商户账户（与 orderId 二选一必填）。 */
    private String accountId;
    /** 关联订单（可选；缺省时按 amount 生成定额码）。 */
    private String orderId;
    /** 金额（分）；有 orderId 时以订单金额为准。 */
    private Long amount;
    /** 幂等键（可选）：同 requestId 重复生成返回同一有效码。 */
    private String requestId;
}