package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-6/X-4 授权与代扣审计留痕（含受理前拒绝 REJECT 痕，拒绝不落悬挂单但必须可查证）。 */
@Data
public class SignAuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String signNo;
    private String deductNo;
    /** SIGN / SIGN_REPLAY / UNSIGN / REVOKE / AGENT_PAY / REJECT / RETRY / TERMINATE / EXPIRE / EVENT。 */
    private String action;
    private String operator;
    private String requestId;
    private Long amount;
    /** OK / REJECT / FAIL。 */
    private String result;
    private String reason;
    private Long createdAt;
}