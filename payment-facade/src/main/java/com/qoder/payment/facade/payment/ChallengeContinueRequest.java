package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 风控 CHALLENGE 续付请求（additive，新端点 /api/payments/challenge/continue）。
 *
 * <p>流程：原受理被风控分级为 CHALLENGE 拒绝（不留悬挂单）→ 收银台 C-3 验证
 * （/api/cashier/verify 签发一次性 verifyToken）→ 以<b>新 requestId</b> 携 token 续付。
 * 服务端经 CashierFacade.payVerified 核销 token（绑定 order+amount+payer）后，
 * 以单_use 续付授权放行同 requestId 的 CHALLENGE 分级；其余分级（REJECT/LIMIT/MANUAL_REVIEW）
 * 一律仍拒绝。/openapi/** 冻结路径不接入本机制（非 PASS 仍受理前拒绝）。
 */
@Data
public class ChallengeContinueRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** BALANCE / CARD。 */
    private String method;
    private String accountId;
    private String cardNo;
    /** 新全局幂等键（必填，区别于被拒绝的原受理 requestId）。 */
    private String requestId;
    private String callbackUrl;
    /** C-3 验证会话令牌（一次性，受理时核销）。 */
    private String verifyToken;
}