package com.qoder.payment.gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 控制台安全链（S-2/G-1，Spring Security + RBAC）：
 * <ul>
 *   <li>/api/** Bearer 会话认证 + 端点域权限点鉴权（未登录 401、越权 403，JSON 错误体）；</li>
 *   <li><b>开放面红线</b>：/openapi/**（HMAC 独立验签，不走 OAuth2/RBAC）、/api/health、
 *       /actuator/**、静态控制台资源一律 permitAll，无 Bearer 可调用；</li>
 *   <li>/api/disputes/**（C 泳道）按 /api/** 统一鉴权天然覆盖：GET=dispute:read，写=dispute:approve；</li>
 *   <li>ADMIN 持通配权限 "*"；会话 STATELESS（token 存 SessionStore：docker Redis / local 内存）。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 端点 → 权限点映射（G-1）：method + ant 模式 + permission，先具体后宽泛。 */
    private static final List<String[]> RULES = List.of(
            // M-1 密钥/证书（member:secret，ADMIN 专属域）
            new String[]{"GET", "/api/members/*/secrets", "member:secret"},
            new String[]{"POST", "/api/members/*/secrets/rotate", "member:secret"},
            new String[]{"GET", "/api/members/*/certs", "member:secret"},
            new String[]{"POST", "/api/members/*/certs", "member:secret"},
            new String[]{"POST", "/api/certs/scan", "member:secret"},
            new String[]{"GET", "/api/certs/expiring", "member:read"},
            new String[]{"GET", "/api/secrets/status", "member:secret"},
            // P1-C 会员/账户/进件（具体路径先于宽泛 /api/members/* 与 /api/accounts）
            new String[]{"POST", "/api/members/*/kyc", "member:write"},
            new String[]{"GET", "/api/members/*/kyc", "member:read"},
            new String[]{"POST", "/api/accounts/typed", "account:write"},
            new String[]{"POST", "/api/accounts/*/status-ex", "account:write"},
            new String[]{"POST", "/api/accounts/*/freeze", "account:write"},
            new String[]{"POST", "/api/accounts/*/unfreeze", "account:write"},
            new String[]{"POST", "/api/accounts/*/level/upgrade", "account:write"},
            new String[]{"GET", "/api/accounts/*/freezes", "account:read"},
            new String[]{"GET", "/api/accounts/*/level", "account:read"},
            new String[]{"GET", "/api/accounts/*/limit-check", "account:read"},
            new String[]{"POST", "/api/accounts/*/cards", "account:write"},
            new String[]{"GET", "/api/accounts/*/cards", "account:read"},
            new String[]{"POST", "/api/accounts/*/cards/*/default", "account:write"},
            new String[]{"POST", "/api/cards/*/unbind", "account:write"},
            // P2-G 账户生命周期（销户五重护栏 + fail-closed，升降级联动 KYC）
            new String[]{"POST", "/api/accounts/*/close", "account:write"},
            new String[]{"POST", "/api/accounts/*/level", "account:write"},
            new String[]{"GET", "/api/accounts/*/lifecycle-logs", "account:read"},
            new String[]{"GET", "/api/accounts/*", "account:read"},
            // 商户进件审核流（X-2）：审核动作独立权限点，OPERATOR 以上
            new String[]{null, "/api/merchant/review/**", "merchant:review"},
            new String[]{"GET", "/api/merchant/**", "merchant:read"},
            new String[]{"POST", "/api/merchant/**", "merchant:write"},
            // 会员/账户
            new String[]{"GET", "/api/members", "member:read"},
            new String[]{"GET", "/api/members/*", "member:read"},
            new String[]{"POST", "/api/members", "member:write"},
            new String[]{"POST", "/api/members/*/status", "member:write"},
            new String[]{"GET", "/api/accounts", "account:read"},
            new String[]{"POST", "/api/accounts", "account:write"},
            new String[]{"POST", "/api/accounts/*/status", "account:write"},
            // 账务
            new String[]{"POST", "/api/accounting/internal/topup", "accounting:topup"},
            new String[]{"POST", "/api/accounting/transfers/*/reverse", "accounting:reverse"},
            new String[]{"POST", "/api/accounting/suspense", "accounting:suspense"},
            // P1-E 账务运营（科目映射/日切日终/账实核对），控制器内另强制 X-Step-Up-Token
            new String[]{"POST", "/api/accounting/subject-mapping", "accounting:subject"},
            new String[]{"POST", "/api/accounting/day-cut", "accounting:daycut"},
            new String[]{"POST", "/api/accounting/day-end", "accounting:daycut"},
            new String[]{"POST", "/api/accounting/reserve-recon", "accounting:reserve"},
            // P2-C 多币种：汇率维护与换汇（真实动账，控制器内另强制 step-up）
            new String[]{"POST", "/api/accounting/fx-rate", "accounting:exchange"},
            new String[]{"POST", "/api/accounting/exchange", "accounting:exchange"},
            new String[]{"POST", "/api/accounts/currency", "account:write"},
            new String[]{"GET", "/api/accounting/**", "accounting:read"},
            // 交易/收银台
            new String[]{"GET", "/api/orders", "trade:read"},
            new String[]{"GET", "/api/orders/*", "trade:read"},
            new String[]{"POST", "/api/orders", "trade:write"},
            new String[]{"POST", "/api/orders/*/close", "trade:write"},
            new String[]{"GET", "/api/cashier", "payment:read"},
            new String[]{"POST", "/api/cashier/pay", "payment:pay"},
            // 支付
            new String[]{"GET", "/api/payments", "payment:read"},
            new String[]{"GET", "/api/payments/*", "payment:read"},
            new String[]{"POST", "/api/payments/*/refund", "payment:refund"},
            new String[]{"POST", "/api/payments/*/reconverge", "payment:reconverge"},
            // 渠道
            new String[]{"GET", "/api/channel/**", "channel:read"},
            new String[]{"POST", "/api/channel/receipt", "channel:receipt"},
            // P1-A 渠道运营（限额/维护窗/账单下载与比对）；具体 receipt 规则在前优先匹配
            new String[]{"POST", "/api/channel/**", "channel:write"},
            new String[]{"PUT", "/api/channel/**", "channel:write"},
            new String[]{"DELETE", "/api/channel/**", "channel:write"},
            // P2-E 营销抵扣编排（整单抵扣，真实动账走既有支付/退款权限点）
            new String[]{"POST", "/api/marketing-pay/pay", "payment:pay"},
            new String[]{"POST", "/api/marketing-pay/rollback/*", "payment:refund"},
            new String[]{"POST", "/api/marketing-pay/realloc/*", "marketing:write"},
            new String[]{"POST", "/api/marketing-pay/best", "marketing:read"},
            new String[]{"GET", "/api/marketing-pay/**", "marketing:read"},
            // P2-A 营销域（券/红包/积分/活动/分摊），敏感写控制器内另强制 X-Step-Up-Token
            new String[]{"GET", "/api/marketing/**", "marketing:read"},
            new String[]{"POST", "/api/marketing/**", "marketing:write"},
            new String[]{"PUT", "/api/marketing/**", "marketing:write"},
            new String[]{"DELETE", "/api/marketing/**", "marketing:write"},
            // P1-B 风控运营（规则/限额/名单/评分/灰度回滚）
            new String[]{"GET", "/api/risk/**", "risk:read"},
            new String[]{"POST", "/api/risk/**", "risk:write"},
            new String[]{"PUT", "/api/risk/**", "risk:write"},
            new String[]{"DELETE", "/api/risk/**", "risk:write"},
            // 清结算/通知/对账
            new String[]{"GET", "/api/settle/**", "settle:read"},
            new String[]{"POST", "/api/settle/**", "settle:write"},
            new String[]{"PUT", "/api/settle/**", "settle:write"},
            new String[]{"DELETE", "/api/settle/**", "settle:write"},
            new String[]{"GET", "/api/notify/**", "notify:read"},
            new String[]{"POST", "/api/notify/send", "notify:send"},
            // N-4 additive：通知重发（单条/批量）沿用 notify:send 权限点，另叠加 step-up 闸门
            new String[]{"POST", "/api/notify/*/resend", "notify:send"},
            new String[]{"POST", "/api/notify/resend/batch", "notify:send"},
            new String[]{"GET", "/api/recon/latest", "recon:read"},
            new String[]{"POST", "/api/recon/run", "recon:run"},
            // P1-J 对账运营（日切批量/渠道内外/报表/分片）与既有 run 同权限点
            new String[]{"POST", "/api/recon/**", "recon:run"},
            new String[]{"GET", "/api/recon/**", "recon:read"},
            // 差错/争议（C 泳道 DisputeController，统一覆盖）
            new String[]{"GET", "/api/disputes/**", "dispute:read"},
            new String[]{"POST", "/api/disputes/**", "dispute:approve"},
            new String[]{"PUT", "/api/disputes/**", "dispute:approve"},
            new String[]{"DELETE", "/api/disputes/**", "dispute:approve"},
            // 审计/控制台管理
            new String[]{"GET", "/api/audit", "audit:read"},
            new String[]{null, "/api/console/**", "console:admin"},
            // I-9 additive：灰度配置/一键回滚属运维管理面，ADMIN 专属
            new String[]{null, "/api/gray/**", "console:admin"},
            // G-4a additive（P2-B 开放平台）：应用注册/授权/配额/账单管理面，复用商户域权限点
            // （ADMIN 通配 "*" 天然覆盖；OPERATOR 持 merchant:write 可运营，AUDITOR/READONLY 只读）
            new String[]{"GET", "/api/openplatform/**", "merchant:read"},
            new String[]{"POST", "/api/openplatform/**", "merchant:write"},
            new String[]{"PUT", "/api/openplatform/**", "merchant:write"},
            new String[]{"DELETE", "/api/openplatform/**", "merchant:write"}
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionStore sessionStore,
                                                   AuditSupport auditSupport) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(reg -> {
                    // 开放面红线：健康/登录/登出免鉴权（回归脚本与 ops-pro 监控依赖）
                    reg.requestMatchers("/api/health", "/api/auth/login", "/api/auth/logout").permitAll();
                    // G-2 additive：API 文档端点免鉴权（schema 已在生成期脱敏，见 OpenApiDocConfig）
                    reg.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    // P1-C C 端会员认证流（非控制台会话）：注册/短信码/登录/登出/会话/改密免控制台 Bearer，
                    // 会员自身会话与改密旧口令校验在 MemberAuthController 内完成。
                    reg.requestMatchers("/api/member/register", "/api/member/sms-code", "/api/member/login",
                            "/api/member/logout", "/api/member/session", "/api/member/password").permitAll();
                    reg.requestMatchers("/api/auth/me", "/api/auth/refresh", "/api/stats").authenticated();
                    for (String[] rule : RULES) {
                        AuthorizationManager<RequestAuthorizationContext> manager =
                                AuthorityAuthorizationManager.hasAnyAuthority(rule[2], "*");
                        if (rule[0] == null) {
                            reg.requestMatchers(rule[1]).access(manager);
                        } else {
                            reg.requestMatchers(HttpMethod.valueOf(rule[0]), rule[1]).access(manager);
                        }
                    }
                    // 兜底：其余 /api/**（含未来新端点）至少需登录
                    reg.requestMatchers("/api/**").authenticated();
                    // /openapi/**（HMAC 独立链）、/actuator/**、静态资源、/error 免鉴权
                    reg.anyRequest().permitAll();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            auditSupport.write(AuditSupport.currentUsername(), request.getMethod(),
                                    request.getMethod(), request.getRequestURI(), null, null, "401",
                                    AuditSupport.ipOf(request), AuditSupport.traceIdOf(response));
                            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话已过期", "A0401");
                        })
                        .accessDeniedHandler((request, response, deniedException) -> {
                            auditSupport.write(AuditSupport.currentUsername(), request.getMethod(),
                                    request.getMethod(), request.getRequestURI(), null, null, "403",
                                    AuditSupport.ipOf(request), AuditSupport.traceIdOf(response));
                            deny(response, HttpServletResponse.SC_FORBIDDEN, "无权限执行该操作", "A0403");
                        }))
                .addFilterBefore(new BearerSessionFilter(sessionStore), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 审计过滤器注册在 Security 链（-100）之后：能取到登录态与最终 HTTP 状态。 */
    @Bean
    public FilterRegistrationBean<AuditFilter> auditFilterRegistration(AuditSupport auditSupport) {
        FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>(new AuditFilter(auditSupport));
        registration.setOrder(-50);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    private static void deny(HttpServletResponse response, int status, String message, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // G-3 additive：错误体追加 traceId（与 X-Trace-Id 同值），error/code 口径不变
        String traceId = AuditSupport.traceIdOf(response);
        String traceField = traceId == null || traceId.isBlank() ? "" : ",\"traceId\":\"" + traceId + "\"";
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"" + traceField + "}");
    }
}