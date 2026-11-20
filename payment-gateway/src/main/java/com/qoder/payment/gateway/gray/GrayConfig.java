package com.qoder.payment.gateway.gray;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * I-9 灰度转发装配：过滤器 order=-200（早于 Spring Security -100），
 * 只拦 {@code /api/*} 与 {@code /openapi/*}；健康/actuator/文档/静态资源不经灰度（监控与回滚通道常在）。
 *
 * <p>默认 {@code payment.gray.enabled=false}（回滚态），local/docker 同构零依赖；
 * docker 灰度状态入共享 Redis，多实例一致、一键回滚即时生效。
 */
@Configuration
public class GrayConfig {

    @Bean
    public FilterRegistrationBean<GrayForwardFilter> grayForwardFilterRegistration(GrayRouter router) {
        FilterRegistrationBean<GrayForwardFilter> registration =
                new FilterRegistrationBean<>(new GrayForwardFilter(router));
        registration.addUrlPatterns("/api/*", "/openapi/*");
        registration.setOrder(-200);
        registration.setName("grayForwardFilter");
        return registration;
    }
}