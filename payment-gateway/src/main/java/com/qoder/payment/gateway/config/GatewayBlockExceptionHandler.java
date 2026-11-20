package com.qoder.payment.gateway.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

/**
 * I-7 Web 层 Sentinel 拒绝统一错误体（与 GatewaySupport/GlobalExceptionHandler 同口径 {error, code}）。
 * 限流 429 A0429；熔断/系统保护 503 B0003；授权规则 403 A0403。不泄漏堆栈与规则细节。
 * traceId 由 TraceWebFilter 统一落 X-Trace-Id 响应头（含被拒请求）。
 */
public class GatewayBlockExceptionHandler implements BlockExceptionHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, BlockException e) throws Exception {
        String resource = e.getRule() == null ? request.getRequestURI() : e.getRule().getResource();
        MetricsSupport.count("sentinel_block", "type", e.getClass().getSimpleName(), "resource", resource);
        HttpStatus status;
        String code;
        String message;
        if (e instanceof FlowException || e instanceof ParamFlowException) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            code = ResultCode.TOO_MANY_REQUESTS.getCode();
            message = ResultCode.TOO_MANY_REQUESTS.getMessage();
        } else if (e instanceof AuthorityException) {
            status = HttpStatus.FORBIDDEN;
            code = ResultCode.FORBIDDEN.getCode();
            message = ResultCode.FORBIDDEN.getMessage();
        } else if (e instanceof DegradeException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            code = ResultCode.DEGRADED.getCode();
            message = ResultCode.DEGRADED.getMessage();
        } else {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            code = ResultCode.DEGRADED.getCode();
            message = ResultCode.DEGRADED.getMessage();
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}