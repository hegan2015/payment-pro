package com.qoder.payment.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 操作审计过滤器（G-1）：/api/** 写操作（非 GET）落 audit_log，
 * who（当前登录用户）/when（member 域落库时间）/what（方法+路径+关键 bizNo）/结果（HTTP 状态）/IP/traceId。
 * <p>注册在 Spring Security 链之后（能取到登录态；401/403 拒绝由 EntryPoint/DeniedHandler 另行审计）。
 * 登录/登出由 AuthController 语义审计（LOGIN/LOGIN_FAIL/LOGOUT），此处跳过避免重复。
 * bizNo 提取：路径主键段 + body 的 bizNo/requestId/outTradeNo/orderId/paymentId + 查询串 bizNo；
 * 绝不记录请求体原文（防口令/密钥入审计）。
 */
public class AuditFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> NON_ID_SEGMENTS = Set.of(
            "api", "members", "accounts", "accounting", "orders", "payments", "cashier", "channel",
            "settle", "notify", "recon", "audit", "auth", "console", "certs", "secrets", "disputes",
            "internal", "transfers", "entries", "users", "roles", "trades", "records", "summary",
            "status", "refund", "reverse", "close", "pay", "rotate", "revoke", "reconverge",
            "receipt", "send", "topup", "suspense", "trial", "balance", "run", "latest", "scan",
            "expiring", "login", "logout", "refresh", "me", "health", "stats", "nope");
    private static final String[] BIZ_FIELDS = {"bizNo", "requestId", "outTradeNo", "orderId", "paymentId"};

    private final AuditSupport auditSupport;

    public AuditFilter(AuditSupport auditSupport) {
        this.auditSupport = auditSupport;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean auditable = path.startsWith("/api/")
                && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method)
                && !path.equals("/api/auth/login") && !path.equals("/api/auth/logout")
                && !path.equals("/api/auth/refresh") && !path.equals("/api/health");
        if (!auditable) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest effective = request;
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            effective = new ContentCachingRequestWrapper(request, 65536);
        }
        try {
            chain.doFilter(effective, response);
        } finally {
            try {
                String bizNo = extractBizNo(path, request.getQueryString(), effective);
                String query = request.getQueryString();
                String detail = query == null ? null
                        : (query.length() > 500 ? query.substring(0, 500) : query);
                auditSupport.write(AuditSupport.currentUsername(), method, method, path, bizNo, detail,
                        String.valueOf(response.getStatus()), AuditSupport.ipOf(request),
                        AuditSupport.traceIdOf(response));
            } catch (Exception ignored) {
                // 审计失败不影响业务（AuditSupport 内已兜底告警）
            }
        }
    }

    private static String extractBizNo(String path, String query, HttpServletRequest request) {
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !NON_ID_SEGMENTS.contains(segment.toLowerCase())) {
                return segment;
            }
        }
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "bizNo".equals(pair.substring(0, eq))) {
                    return pair.substring(eq + 1);
                }
            }
        }
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] body = wrapper.getContentAsByteArray();
            if (body.length > 0) {
                try {
                    JsonNode node = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
                    for (String field : BIZ_FIELDS) {
                        if (node.hasNonNull(field) && node.get(field).isValueNode()) {
                            return node.get(field).asText();
                        }
                    }
                } catch (Exception ignored) {
                    // 非法 JSON 不影响审计主体
                }
            }
        }
        return null;
    }
}