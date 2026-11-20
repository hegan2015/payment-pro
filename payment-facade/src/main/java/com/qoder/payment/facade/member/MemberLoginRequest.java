package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * M-5 C 端登录请求：口令模式 principal（手机号/会员 id）+password；
 * 短信模式 mobile+smsCode（沙箱验证码）。冻结会员拒绝登录。
 */
@Data
public class MemberLoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 手机号或会员 id（口令模式）。 */
    private String principal;
    private String password;
    /** 短信模式手机号。 */
    private String mobile;
    private String smsCode;
}