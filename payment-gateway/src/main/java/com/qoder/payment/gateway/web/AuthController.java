package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.auth.AuthFacade;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.gateway.security.AuditSupport;
import com.qoder.payment.gateway.security.BearerSessionFilter;
import com.qoder.payment.gateway.security.LoginRateLimiter;
import com.qoder.payment.gateway.security.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台认证端点（S-2）：login/logout/me/refresh。
 * Bearer 会话 token（随机 64hex）存 SessionStore（docker Redis / local 内存）；
 * 口令校验经 AuthFacade（member 域 BCrypt），明文口令不落网关日志/审计。
 * 登录失败限流（用户名+IP 滑动窗口）+ 成功/失败均审计。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    @DubboReference
    private AuthFacade authFacade;

    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final AuditSupport auditSupport;

    @Value("${payment.auth.session.ttl-seconds:3600}")
    private long ttlSeconds;

    public AuthController(SessionStore sessionStore, LoginRateLimiter rateLimiter, AuditSupport auditSupport) {
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.auditSupport = auditSupport;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request, HttpServletResponse response) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        String ip = AuditSupport.ipOf(request);
        String traceId = traceId(response);
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username/password 必填", "code", "A0400"));
        }
        if (rateLimiter.blocked(username, ip)) {
            auditSupport.write(username, "LOGIN_FAIL", "POST", "/api/auth/login", null, null, "429", ip, traceId);
            return ResponseEntity.status(429).body(Map.of("error", "登录失败次数过多，请稍后再试", "code", "A0429"));
        }
        Result<ConsoleUserDTO> result = authFacade.authenticate(username, password);
        if (!result.isSuccess()) {
            rateLimiter.recordFailure(username, ip);
            auditSupport.write(username, "LOGIN_FAIL", "POST", "/api/auth/login", null, null, "401", ip, traceId);
            return ResponseEntity.status(401).body(Map.of("error", result.getMessage(), "code", "A0401"));
        }
        rateLimiter.reset(username, ip);
        ConsoleUserDTO user = result.getData();
        String token = newToken();
        sessionStore.save(token, user, ttlSeconds);
        auditSupport.write(username, "LOGIN", "POST", "/api/auth/login", null, null, "200", ip, traceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresIn", ttlSeconds);
        data.put("user", user);
        return ResponseEntity.ok(data);
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = tokenOf(request);
        String username = AuditSupport.currentUsername();
        if (token != null) {
            sessionStore.delete(token);
        }
        SecurityContextHolder.clearContext();
        auditSupport.write(username, "LOGOUT", "POST", "/api/auth/logout", null, null, "200",
                AuditSupport.ipOf(request), traceId(response));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me() {
        ConsoleUserDTO user = currentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期", "code", "A0401"));
        }
        // 回源取最新角色/权限（授权变更后无需重新登录）
        Result<ConsoleUserDTO> fresh = authFacade.getUser(user.getUsername());
        return ResponseEntity.ok(fresh.isSuccess() ? fresh.getData() : user);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        ConsoleUserDTO user = currentUser();
        String oldToken = tokenOf(request);
        if (user == null || oldToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期", "code", "A0401"));
        }
        String token = newToken();
        sessionStore.save(token, user, ttlSeconds);
        sessionStore.delete(oldToken);
        auditSupport.write(user.getUsername(), "REFRESH", "POST", "/api/auth/refresh", null, null, "200",
                AuditSupport.ipOf(request), traceId(response));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresIn", ttlSeconds);
        data.put("user", user);
        return ResponseEntity.ok(data);
    }

    private static ConsoleUserDTO currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ConsoleUserDTO user ? user : null;
    }

    private static String tokenOf(HttpServletRequest request) {
        Object attr = request.getAttribute(BearerSessionFilter.TOKEN_ATTR);
        if (attr != null) {
            return attr.toString();
        }
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static String traceId(HttpServletResponse response) {
        String fromResponse = response.getHeader("X-Trace-Id");
        if (fromResponse != null) {
            return fromResponse;
        }
        String fromMdc = MDC.get("traceId");
        return fromMdc == null ? "" : fromMdc;
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
    }
}