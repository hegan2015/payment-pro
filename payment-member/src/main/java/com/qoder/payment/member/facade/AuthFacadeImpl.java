package com.qoder.payment.member.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.auth.AuthFacade;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.facade.auth.CreateUserRequest;
import com.qoder.payment.facade.auth.RoleDTO;
import com.qoder.payment.member.service.ConsoleAuthService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/** 控制台认证/用户/角色（S-2/G-1），member 域实现。 */
@DubboService
@RequiredArgsConstructor
public class AuthFacadeImpl implements AuthFacade {

    private final ConsoleAuthService consoleAuthService;

    @Override
    public Result<ConsoleUserDTO> authenticate(String username, String password) {
        return FacadeSupport.execute(() -> consoleAuthService.authenticate(username, password));
    }

    @Override
    public Result<ConsoleUserDTO> getUser(String username) {
        return FacadeSupport.execute(() -> consoleAuthService.getUser(username));
    }

    @Override
    public Result<ConsoleUserDTO> createUser(CreateUserRequest request) {
        return FacadeSupport.execute(() -> consoleAuthService.createUser(request));
    }

    @Override
    public Result<List<ConsoleUserDTO>> listUsers() {
        return FacadeSupport.execute(consoleAuthService::listUsers);
    }

    @Override
    public Result<ConsoleUserDTO> grantRole(String userId, String roleCode) {
        return FacadeSupport.execute(() -> consoleAuthService.grantRole(userId, roleCode));
    }

    @Override
    public Result<ConsoleUserDTO> revokeRole(String userId, String roleCode) {
        return FacadeSupport.execute(() -> consoleAuthService.revokeRole(userId, roleCode));
    }

    @Override
    public Result<ConsoleUserDTO> updateUserStatus(String userId, String status) {
        return FacadeSupport.execute(() -> consoleAuthService.updateUserStatus(userId, status));
    }

    @Override
    public Result<List<RoleDTO>> listRoles() {
        return FacadeSupport.execute(consoleAuthService::listRoles);
    }
}