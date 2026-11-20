package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 控制台用户（S-2）：与业务会员分离，落 pp_member 库 console_* 表。不含口令哈希。 */
@Data
public class ConsoleUserDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String status;
    private List<String> roles;
    private List<String> permissions;
    private Long createdAt;
}