package com.qoder.payment.gateway.security;

import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.auth.ConsoleFacade;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 审计写入支撑（G-1）：who/when/what(方法+路径+关键 bizNo)/结果/IP/traceId → member 域 audit_log。
 * 审计失败只告警不影响业务；detail 只放查询串，严禁携带口令/密钥明文。
 */
@Slf4j
@Component
public class AuditSupport {

    @DubboReference
    private ConsoleFacade consoleFacade;

    public void write(String username, String action, String method, String path,
                      String bizNo, String detail, String result, String ip, String traceId) {
        try {
            AuditWriteRequest request = new AuditWriteRequest();
            request.setUsername(username);
            request.setAction(action);
            request.setMethod(method);
            request.setPath(path);
            request.setBizNo(bizNo);
            request.setDetail(detail);
            request.setResult(result);
            request.setIp(ip);
            request.setTraceId(traceId);
            consoleFacade.audit(request);
        } catch (Exception e) {
            log.warn("审计写入失败（不影响业务） path={}: {}", path, e.getMessage());
        }
    }

    /** 从当前安全上下文取操作人（未登录 anonymous）。 */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ConsoleUserDTO user) {
            return user.getUsername();
        }
        return "anonymous";
    }

    public static String traceIdOf(HttpServletResponse response) {
        String traceId = response.getHeader("X-Trace-Id");
        return traceId == null ? "" : traceId;
    }

    public static String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}