package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-5 C 端用户注册（手机号+口令；区别于控制台 console_user）。口令 BCrypt 落库。 */
@Data
public class MemberRegisterRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String mobile;
    /** ≥8 位；明文仅传输，绝不落库/落日志。 */
    private String password;
}