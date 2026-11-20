package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-5 会话查询出参（不含 token 本身以外的敏感信息）。 */
@Data
public class MemberSessionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String name;
    private String mobileMask;
    private Long expiresAt;
    private Boolean valid;
}