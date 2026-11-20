package com.qoder.payment.gateway.web;

import com.qoder.payment.common.trace.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP 入口 traceId：落 MDC 并经 Dubbo attachment 透传全链路。
 *
 * <p>G-3/I-9：order=-300，早于灰度转发(-200)与 Spring Security(-100)——
 * 401/403 短路响应与灰度转发响应也带 X-Trace-Id，统一错误体 traceId 恒可追溯。
 */
@Component
@org.springframework.core.annotation.Order(-300)
public class TraceWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TraceFilter.TRACE_ID, traceId);
        RpcContext.getClientAttachment().setAttachment(TraceFilter.TRACE_ID, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceFilter.TRACE_ID);
        }
    }
}