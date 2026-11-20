package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * C-3 支付验证请求（沙箱）：口令 / 短信验证码 / 指纹模拟 / 小额免密。
 * 验证通过签发一次性 verifyToken（绑定 order+amount+payer），供受理衔接。
 */
@Data
public class VerifyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 付款账户（与 memberId 二选一；服务端据此解析会员）。 */
    private String accountId;
    private String memberId;
    private String orderId;
    /** 待支付金额（分）；用于小额免密判定与会话绑定。 */
    private Long amount;
    /** PASSWORD / SMS / BIO / SMALL_AMOUNT_FREE。 */
    private String verifyType;
    /** 支付密码（PASSWORD，沙箱哈希校验）。 */
    private String payPassword;
    /** 短信验证码（SMS，沙箱）。 */
    private String smsCode;
    /** 生物识别端侧令牌（BIO，local 模拟平台校验）。 */
    private String bioToken;
    /** 幂等键（可选）：同 requestId 重复验证返回同一会话。 */
    private String requestId;
}