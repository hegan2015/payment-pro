package com.qoder.payment.gateway.openplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenApiScopes;
import com.qoder.payment.gateway.web.GatewaySupport;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G-4a/G-4b 开放平台闸门（<b>纯 additive 过滤器</b>，{@code OpenApiController} 一个字节都没改）。
 *
 * <p>挂在 {@code /openapi/*}，在既有控制器<b>之前</b>完成：
 * ① 版本协商回显头（G-4b）；② 授权状态（撤销即时拒绝）；③ scope 校验；④ 可选 access_token 授权收敛；
 * ⑤ 沙箱截流（{@link SandboxRouter}，生产账零污染）；⑥ 按 appId QPS/日配额（{@link ApiQuotaGuard}）；
 * ⑦ 计费埋点（{@link ApiBillingService} 异步）。所有拒绝都发生在业务受理之前，不落 PROCESSING 悬挂单。
 *
 * <p><b>ride-pro 兼容红线（最高优先级）</b>：
 * <ul>
 *   <li>未在开放平台注册的 appId（如 appM1）→ 只做免费计数，<b>不校验 scope、不限配额、不进沙箱</b>，
 *       请求原样透传给冻结控制器，八个方法入参/返回/幂等/语义逐字节不变；</li>
 *   <li>请求体字节原样透传（缓存包装），HMAC canonical 不受影响；nonce 不在此消费；</li>
 *   <li>治理组件任何异常 → fail-open 透传，绝不阻断资金流量；</li>
 *   <li>只新增响应头，不改任何响应体结构（account.balance 仍是裸数字 long）。</li>
 * </ul>
 */
@Slf4j
public class OpenPlatformFilter implements Filter {

    private static final Set<String> EXEMPT = Set.of("oauth/token", "oauth/introspect", "version", "scopes");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppPolicyCache policyCache;
    private final ApiQuotaGuard quotaGuard;
    private final ApiBillingService billing;
    private final SandboxRouter sandboxRouter;
    private final ApiVersionRouter versionRouter;
    private final OpenApiAuthService authService;

    public OpenPlatformFilter(AppPolicyCache policyCache, ApiQuotaGuard quotaGuard, ApiBillingService billing,
                              SandboxRouter sandboxRouter, ApiVersionRouter versionRouter,
                              OpenApiAuthService authService) {
        this.policyCache = policyCache;
        this.quotaGuard = quotaGuard;
        this.billing = billing;
        this.sandboxRouter = sandboxRouter;
        this.versionRouter = versionRouter;
        this.authService = authService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        String uri = http.getRequestURI();
        if (uri == null || !uri.startsWith("/openapi/")) {
            chain.doFilter(request, response);
            return;
        }
        String version = versionRouter.effectiveVersion(uri);
        res.setHeader("X-Api-Version", version);
        String requested = http.getHeader(ApiVersionRouter.HEADER);
        if (requested != null && !requested.isBlank() && !requested.equalsIgnoreCase(version)) {
            res.setHeader("X-Api-Version-Requested", requested);
        }
        if (versionRouter.isDeprecated(version)) {
            res.setHeader("X-Api-Deprecated", "true");
            if (versionRouter.sunset() != null) {
                res.setHeader("X-Api-Sunset", versionRouter.sunset());
            }
        }
        String method = ApiVersionRouter.methodOf(uri);
        if (!"POST".equalsIgnoreCase(http.getMethod()) || EXEMPT.contains(method)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyRequest cached = new CachedBodyRequest(http);
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(cached.cachedBody(), Map.class);
        } catch (Exception e) {
            // 非 JSON/空体：交回冻结控制器按既有口径报错（行为不变）
            chain.doFilter(cached, response);
            return;
        }
        Object appIdValue = body == null ? null : body.get("appId");
        if (appIdValue == null || appIdValue.toString().isBlank()) {
            chain.doFilter(cached, response);
            return;
        }
        String appId = appIdValue.toString();

        AppPolicyDTO policy;
        try {
            policy = policyCache.resolve(appId);
        } catch (Exception | LinkageError t) {
            log.warn("开放平台闸门故障，fail-open 透传 appId={}: {}", appId, t.toString());
            chain.doFilter(cached, response);
            return;
        }
        if (!policy.isRegistered()) {
            // 历史商户（ride-pro）：只免费计数，全部新闸门空转
            billing.accept(appId, method, 0L);
            chain.doFilter(cached, response);
            return;
        }

        try {
            if (policy.isRevoked()) {
                deny(res, appId, method, "A0403", "应用授权已撤销: " + appId);
                return;
            }
            if (!OpenApiScopes.allows(policy.getScopes(), method)) {
                deny(res, appId, method, "A0403", "scope 未授权: " + OpenApiScopes.of(method));
                return;
            }
            String token = tokenOf(http, body);
            if (token != null) {
                OpenApiTokenStore.TokenEntry entry = authService.requireToken(token);
                if (!appId.equals(entry.getAppId())) {
                    deny(res, appId, method, "A0403", "access_token 与 appId 不匹配");
                    return;
                }
                if (!OpenApiScopes.allows(entry.getScopes(), method)) {
                    deny(res, appId, method, "A0403", "access_token scope 未授权: " + OpenApiScopes.of(method));
                    return;
                }
            }
            if (policy.isSandbox()) {
                res.setHeader("X-Api-Sandbox", "true");
                ResponseEntity<Object> sandboxed = sandboxRouter.route(appId, method, body, policy);
                if (sandboxed.getStatusCode().is2xxSuccessful()) {
                    billing.accept(appId, method, policy.getUnitPrice() == null ? 0L : policy.getUnitPrice());
                } else {
                    billing.reject(appId, method);
                }
                Object payload = sandboxed.getBody();
                if (ApiVersionRouter.V2.equals(version) && payload != null) {
                    // v2 沙箱同样走 envelope（v1 沙箱保持裸数据形状）
                    payload = Map.of("apiVersion", ApiVersionRouter.V2, "data", payload);
                }
                write(res, sandboxed.getStatusCode().value(), payload);
                return;
            }
            // 注册生产应用：前置验签（防伪造耗配额）→ 配额 → 透传冻结控制器（其再验签一次，口径一致）
            authService.verifySignatureForRegistered(appId, body);
            ApiQuotaGuard.Decision decision = quotaGuard.tryAcquire(appId, policy);
            if (!decision.allowed()) {
                MetricsSupport.count("openplatform_reject", "type", decision.type());
                deny(res, appId, method, "A0429", decision.message());
                return;
            }
        } catch (BizException e) {
            deny(res, appId, method, e.getCode(), e.getMessage());
            return;
        } catch (Exception | LinkageError t) {
            log.warn("开放平台闸门异常，fail-open 透传 appId={} method={}: {}", appId, method, t.toString());
            chain.doFilter(cached, response);
            return;
        }
        billing.accept(appId, method, policy.getUnitPrice() == null ? 0L : policy.getUnitPrice());
        chain.doFilter(cached, response);
    }

    private static String tokenOf(HttpServletRequest http, Map<String, Object> body) {
        String header = http.getHeader("X-Access-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Object value = body.get("access_token");
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private void deny(HttpServletResponse res, String appId, String method, String code, String message)
            throws IOException {
        billing.reject(appId, method);
        log.warn("开放平台拒绝 appId={} method={} code={} reason={}", appId, method, code, message);
        write(res, GatewaySupport.statusOf(code).value(), GatewaySupport.errorBody(message, code));
    }

    private static void write(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (body == null) {
            return;
        }
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(MAPPER.writeValueAsString(body));
    }

    /** 请求体缓存包装：闸门与冻结控制器各读一份，字节原样透传（canonical 不受影响）。 */
    static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] cachedBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 供控制台/SDK 展示闸门作用域（只读）。 */
    public static List<String> exemptPaths() {
        return EXEMPT.stream().sorted().toList();
    }
}