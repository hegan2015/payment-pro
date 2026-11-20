package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-5 C 端会话 token（独立于控制台 Bearer；local 内存 / docker Redis 外置）。 */
@Data
public class MemberTokenDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String token;
    private String memberId;
    private String name;
    private Long expiresIn;
    private Long expiresAt;
}