package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 控制台角色 + 权限矩阵（G-1）。 */
@Data
public class RoleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String code;
    private String name;
    private List<String> permissions;
}