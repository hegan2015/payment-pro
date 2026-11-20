package com.qoder.payment.member.dataobject;

import lombok.Data;

/** 控制台用户（S-2）。passwordHash 仅域内使用，绝不出域/出日志。 */
@Data
public class ConsoleUserDO {

    private String id;
    private String username;
    private String passwordHash;
    private String status;
    private Long createdAt;
}