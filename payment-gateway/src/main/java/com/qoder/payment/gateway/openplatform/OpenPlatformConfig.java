package com.qoder.payment.gateway.openplatform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * G-4a/G-4b 开放平台闸门装配（独立 @Configuration，<b>不改既有 Security/Sentinel/StepUp 任何装配</b>）。
 *
 * <p>order=2：在 StepUp 体缓存过滤器（order=1，仅 /api/*）之后、Sentinel Web 拦截器（HandlerInterceptor，
 * 天然在所有 Filter 之后）之前；只挂 {@code /openapi/*}，控制台 /api/** 与静态页完全不受影响。
 */
@Slf4j
@Configuration
public class OpenPlatformConfig {

    @Bean
    public FilterRegistrationBean<OpenPlatformFilter> openPlatformFilterRegistration(
            AppPolicyCache policyCache, ApiQuotaGuard quotaGuard, ApiBillingService billing,
            SandboxRouter sandboxRouter, ApiVersionRouter versionRouter, OpenApiAuthService authService) {
        FilterRegistrationBean<OpenPlatformFilter> registration = new FilterRegistrationBean<>(
                new OpenPlatformFilter(policyCache, quotaGuard, billing, sandboxRouter, versionRouter, authService));
        registration.addUrlPatterns("/openapi/*");
        registration.setOrder(2);
        registration.setName("openPlatformFilter");
        log.info("开放平台闸门已注册 /openapi/*（授权/scope/沙箱/配额/计费；未注册 appId 全放行）");
        return registration;
    }
}