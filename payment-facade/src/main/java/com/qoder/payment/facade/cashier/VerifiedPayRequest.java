package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-3 验证前置的支付受理请求（H5 收银台专用，<b>新端点 /api/cashier/pay-verified</b>）。
 * 与冻结的 {@link com.qoder.payment.facade.payment.PayRequest} 区分：本请求强制验证衔接，
 * 验证通过后以同一 requestId 委托支付核心，BALANCE 同步语义与 trade.pay 逐字节一致。
 */
@Data
public class VerifiedPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** BALANCE / CARD（BANKCARD 由网关归一）。 */
    private String method;
    private String accountId;
    private String cardNo;
    /** 全局幂等键（必填）：验证与受理同 requestId 不重复扣款。 */
    private String requestId;
    private String callbackUrl;
    /** C-3 验证会话令牌（verify 签发）；与 payPassword 二选一，小额免密可空。 */
    private String verifyToken;
    /** C-3 支付密码（内联验证，沙箱）。 */
    private String payPassword;
}