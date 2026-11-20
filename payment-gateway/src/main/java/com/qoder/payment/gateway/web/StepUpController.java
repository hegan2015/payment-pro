package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.auth.AuthFacade;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.gateway.config.StepUpConfig;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import com.qoder.payment.gateway.security.AuditSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S-7 二次认证 token 颁发：POST /api/security/step-up（口令再验，local 零依赖）。
 * 路径用 /api/security/* 避开 /api/auth/*（登录会话）。
 *
 * <p><b>绑定当前登录会话</b>：再验的是<b>会话用户自己的口令</b>（经 AuthFacade BCrypt 校验），
 * operator 恒取会话用户名，body 里的 username 只允许与会话一致。否则同一人换个用户名 + 全局口令
 * 就能自审自批，S-7 双人复核原则会被绕过。短时 token（默认 300s），Redis docker / 内存 local 降级。
 */
@Slf4j
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class StepUpController {

    private final StepUpTokenStore tokenStore;
    private final StepUpConfig.StepUpProperties properties;

    @DubboReference
    private AuthFacade authFacade;

    @PostMapping("/step-up")
    public ResponseEntity<Object> stepUp(@RequestBody(required = false) Map<String, Object> body) {
        String sessionUser = AuditSupport.currentUsername();
        if (sessionUser == null || "anonymous".equals(sessionUser)) {
            return ResponseEntity.status(401).body(Map.of("error", "二次认证需先登录控制台", "code", "A0401"));
        }
        String password = body == null || body.get("password") == null ? null : body.get("password").toString();
        String claimed = body == null || body.get("username") == null ? null : body.get("username").toString();
        if (claimed != null && !claimed.isBlank() && !claimed.equals(sessionUser)) {
            log.warn("二次认证拒绝：声称用户与会话不符 session={} claimed={}", sessionUser, claimed);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "二次认证必须使用当前登录账号（双人复核不可冒充他人）", "code", "A0403"));
        }
        if (password == null || password.isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "二次认证失败：口令必填", "code", "A0403"));
        }
        Result<ConsoleUserDTO> verified = authFacade.authenticate(sessionUser, password);
        if (verified == null || !verified.isSuccess() || verified.getData() == null) {
            log.warn("二次认证口令再验失败 username={}", sessionUser);
            return ResponseEntity.status(403).body(Map.of("error", "二次认证失败：口令错误", "code", "A0403"));
        }
        String token = tokenStore.issue(sessionUser, properties.getTtlSeconds());
        log.info("二次认证通过 operator={} ttl={}s", sessionUser, properties.getTtlSeconds());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("operator", sessionUser);
        result.put("expiresIn", properties.getTtlSeconds());
        return ResponseEntity.ok(result);
    }
}