package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 创建控制台用户请求（console:admin）。password 仅传输用，落库 BCrypt。 */
@Data
public class CreateUserRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String username;
    private String password;
    private List<String> roles;
}