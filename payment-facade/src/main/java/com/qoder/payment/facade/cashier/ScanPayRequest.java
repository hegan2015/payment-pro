package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-2 扫码受理请求：核销码（一次性 CAS）→ 支付验证（C-3）→ 调支付核心（幂等 requestId）。
 * 被扫：付款方取自码绑定账户；主扫：付款方取自本请求 payerAccountId。
 */
@Data
public class ScanPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待核销码值。 */
    private String code;
    /** 全局幂等键（必填）：同 requestId 不重复扣款。 */
    private String requestId;
    /** 主扫时的付款账户（被扫可空，取码绑定账户）。 */
    private String payerAccountId;
    /** C-3 验证会话令牌（verify 通过后签发）；与 payPassword 二选一。 */
    private String verifyToken;
    /** C-3 支付密码（内联验证，沙箱）；小额免密时可空。 */
    private String payPassword;
    /** 支付方式，缺省 BALANCE（沙箱扫码走余额同步语义，与 trade.pay 一致）。 */
    private String method;
    private String cardNo;
    private String callbackUrl;
}