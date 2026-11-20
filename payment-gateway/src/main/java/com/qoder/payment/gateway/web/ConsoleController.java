package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.auth.AuthFacade;
import com.qoder.payment.facade.auth.CreateUserRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 控制台用户/角色管理（S-2/G-1，console:admin，ADMIN 专属）。口令仅传输，BCrypt 落 member 库。 */
@RestController
@RequestMapping("/api/console")
public class ConsoleController {

    @DubboReference
    private AuthFacade authFacade;

    @GetMapping("/users")
    public ResponseEntity<Object> users() {
        return GatewaySupport.of(authFacade.listUsers());
    }

    @PostMapping("/users")
    public ResponseEntity<Object> createUser(@RequestBody CreateUserRequest request) {
        return GatewaySupport.of(authFacade.createUser(request));
    }

    @GetMapping("/roles")
    public ResponseEntity<Object> roles() {
        return GatewaySupport.of(authFacade.listRoles());
    }

    @PostMapping("/users/{id}/roles")
    public ResponseEntity<Object> grantRole(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(authFacade.grantRole(id, str(body.get("role"))));
    }

    @PostMapping("/users/{id}/roles/revoke")
    public ResponseEntity<Object> revokeRole(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(authFacade.revokeRole(id, str(body.get("role"))));
    }

    @PostMapping("/users/{id}/status")
    public ResponseEntity<Object> status(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(authFacade.updateUserStatus(id, str(body.get("status"))));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}