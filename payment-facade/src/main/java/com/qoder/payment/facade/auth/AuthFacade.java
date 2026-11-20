package com.qoder.payment.facade.auth;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 控制台认证与用户/角色管理（S-2/G-1），member 域实现（console_* 表落 pp_member 库）。
 * <p>口令 BCrypt 校验在 member 域内完成，明文口令不出现在任何返回 DTO/日志；
 * 会话 token 的签发/存储由网关负责（docker Redis / local 内存）。
 */
public interface AuthFacade {

    /** 校验用户名+口令，成功返回用户（含 roles/permissions）；失败 A0401。 */
    Result<ConsoleUserDTO> authenticate(String username, String password);

    Result<ConsoleUserDTO> getUser(String username);

    Result<ConsoleUserDTO> createUser(CreateUserRequest request);

    Result<List<ConsoleUserDTO>> listUsers();

    Result<ConsoleUserDTO> grantRole(String userId, String roleCode);

    Result<ConsoleUserDTO> revokeRole(String userId, String roleCode);

    Result<ConsoleUserDTO> updateUserStatus(String userId, String status);

    Result<List<RoleDTO>> listRoles();
}