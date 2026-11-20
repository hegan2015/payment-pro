package com.qoder.payment.gateway.config;

import com.qoder.payment.gateway.web.StepUpAuthInterceptor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * S-7 敏感操作二次认证装配（独立 @Configuration，不改既有 security 过滤链）。
 *
 * <p>注册 {@link StepUpAuthInterceptor}（/api/**）：退款/冲正大额、复核 approve/reject、
 * 备付金模拟调整、密钥轮换恒需 X-Step-Up-Token。token 由 POST /api/security/step-up
 * 口令再验颁发（短时，Redis docker / 内存 local 降级）。缺失/失效 → 403。
 * /openapi/** 完全不受影响（§0.7 兼容红线）。
 *
 * <p>配套请求体缓存过滤器：拦截器需在控制器消费前读取退款金额（幂等键 requestId），
 * 仅对 /api/* 的 POST 包装缓存体，字节流原样透传，不改既有请求语义。
 */
@Configuration
public class StepUpConfig implements WebMvcConfigurer {

    @Autowired
    private StepUpAuthInterceptor stepUpAuthInterceptor;

    @Bean
    @ConfigurationProperties(prefix = "payment.step-up")
    public StepUpProperties stepUpProperties() {
        return new StepUpProperties();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpAuthInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    public FilterRegistrationBean<CachedBodyFilter> stepUpCachedBodyFilter() {
        FilterRegistrationBean<CachedBodyFilter> registration = new FilterRegistrationBean<>(new CachedBodyFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setName("stepUpCachedBodyFilter");
        return registration;
    }

    /** 二次认证配置（口令再验走会话用户自己的凭据，见 StepUpController；此处只配 token 生命周期）。 */
    @Data
    public static class StepUpProperties {
        /** token 有效期（秒），默认 5 分钟。 */
        private long ttlSeconds = 300;
    }

    /** /api/* POST 请求体缓存：拦截器与控制器各读一份，互不消耗。 */
    public static class CachedBodyFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request instanceof HttpServletRequest http && "POST".equalsIgnoreCase(http.getMethod())) {
                chain.doFilter(new CachedBodyRequest(http), response);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** 缓存体请求包装（字节全量缓存，流可重复读）。 */
    public static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        public CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        public byte[] cachedBody() {
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
}