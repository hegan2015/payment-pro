package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** C-3 验证结果：pass=false 时不得受理支付；pass=true 签发一次性 verifyToken。 */
@Data
public class VerifyResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean pass;
    /** 触发的验证类型 / 挑战类型：PASSWORD / SMS / BIO / SMALL_AMOUNT_FREE / LOCKED / NEED_SET_PASSWORD。 */
    private String challengeType;
    /** 验证会话令牌（一次性，受理时核销）。 */
    private String verifyToken;
    private String sessionId;
    private Long expireAt;
    /** 失败/拦截原因（明确文案，供 H5 展示与重试）。 */
    private String reason;
    /** 小额免密限额（分）。 */
    private Long freeLimit;
    /** 剩余可失败次数（口令暴力防护）。 */
    private Integer remainAttempts;
    /** 锁定截止时间（毫秒，口令连续失败触发）。 */
    private Long lockedUntil;
}