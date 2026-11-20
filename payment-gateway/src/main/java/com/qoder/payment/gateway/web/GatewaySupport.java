package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.trace.TraceFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dubbo Result → HTTP 响应适配：错误码映射状态码，错误体兼容前端 {error, code}。
 *
 * <p><b>G-3 additive</b>：错误体追加 {@code traceId}（与响应头 X-Trace-Id 同值，便于追溯）；
 * <b>成功响应结构一字不改</b>——仍是裸数据（{@code result.getData()}），不套 envelope，
 * {@code account.balance} 仍是裸数字 long。新增码映射只 additive（见 {@link ErrorCodeRegistry}）。
 */
public final class GatewaySupport {

    private GatewaySupport() {
    }

    public static <T> ResponseEntity<Object> of(Result<T> result) {
        if (result.isSuccess()) {
            return ResponseEntity.ok(result.getData());
        }
        return ResponseEntity.status(statusOf(result.getCode()))
                .body(errorBody(result.getMessage(), result.getCode()));
    }

    /** 统一错误体：{error, code, traceId}（traceId 缺失时省略，绝不输出堆栈/下游地址）。 */
    public static Map<String, Object> errorBody(String message, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "请求处理失败" : message);
        body.put("code", code == null ? "B0002" : code);
        String traceId = traceId();
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        return body;
    }

    public static String traceId() {
        String mdc = MDC.get(TraceFilter.TRACE_ID);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        // Dubbo TraceFilter 在 RPC 返回后清理 MDC，故回退读 TraceWebFilter 已写入的响应头
        // （TraceWebFilter order=-300，早于 Security/灰度，401/403 短路响应亦带该头）。
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String header = servletAttributes.getResponse() == null
                    ? null : servletAttributes.getResponse().getHeader("X-Trace-Id");
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return null;
    }

    public static HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case "A0400" -> HttpStatus.BAD_REQUEST;
            case "A0401" -> HttpStatus.UNAUTHORIZED;
            case "A0403" -> HttpStatus.FORBIDDEN;
            case "A0404" -> HttpStatus.NOT_FOUND;
            case "A0409" -> HttpStatus.CONFLICT;
            case "A0429" -> HttpStatus.TOO_MANY_REQUESTS;
            case "B0003" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "C0001" -> HttpStatus.BAD_GATEWAY;
            case "B0002" -> HttpStatus.INTERNAL_SERVER_ERROR;
            // ---- G-3 additive 子码（既有映射不变） ----
            case "A0428" -> HttpStatus.BAD_REQUEST;
            case "A0405" -> HttpStatus.METHOD_NOT_ALLOWED;
            case "A0413" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "B0004" -> HttpStatus.FORBIDDEN;
            case "C0002" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}