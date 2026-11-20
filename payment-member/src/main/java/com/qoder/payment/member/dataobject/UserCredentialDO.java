package com.qoder.payment.member.dataobject;

import lombok.Data;

/** M-5 C 端登录凭证（区别于 console_user）：口令 BCrypt，手机号哈希+掩码；失败计数/锁定。 */
@Data
public class UserCredentialDO {

    private String memberId;
    private String mobileHash;
    private String mobileMask;
    private String passwordHash;
    private Integer failCount;
    private Long lockedUntil;
    private Long createdAt;
    private Long updatedAt;
}