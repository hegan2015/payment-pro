package com.qoder.payment.gateway.web;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * G-3 统一异常映射：错误体恒为 {@code {error, code, traceId}}（{@link GatewaySupport#errorBody}），
 * <b>绝不泄漏堆栈、下游地址、连接串、密钥</b>——细节只落服务端日志。
 * 既有码/状态映射保持不变，新增子码只 additive（{@link ErrorCodeRegistry}）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> biz(BizException e) {
        return ResponseEntity.status(GatewaySupport.statusOf(e.getCode()))
                .body(GatewaySupport.errorBody(e.getMessage(), e.getCode()));
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Object> rpc(RpcException e) {
        // I-7：细节只落服务端日志，响应不泄漏下游地址/堆栈
        log.warn("下游 Dubbo 调用失败: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(GatewaySupport.errorBody("下游服务暂不可达，请稍后重试", ResultCode.DOWNSTREAM_ERROR.getCode()));
    }

    /** I-7 兜底：Sentinel 拒绝正常由拦截器/fallback 处理，此处防御性映射同口径错误体。 */
    @ExceptionHandler(BlockException.class)
    public ResponseEntity<Object> sentinelBlock(BlockException e) {
        if (e instanceof FlowException || e instanceof ParamFlowException) {
            return ResponseEntity.status(429)
                    .body(GatewaySupport.errorBody(ResultCode.TOO_MANY_REQUESTS.getMessage(), ResultCode.TOO_MANY_REQUESTS.getCode()));
        }
        if (e instanceof AuthorityException) {
            return ResponseEntity.status(403)
                    .body(GatewaySupport.errorBody(ResultCode.FORBIDDEN.getMessage(), ResultCode.FORBIDDEN.getCode()));
        }
        if (e instanceof DegradeException) {
            return ResponseEntity.status(503)
                    .body(GatewaySupport.errorBody(ResultCode.DEGRADED.getMessage(), ResultCode.DEGRADED.getCode()));
        }
        return ResponseEntity.status(503)
                .body(GatewaySupport.errorBody(ResultCode.DEGRADED.getMessage(), ResultCode.DEGRADED.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(GatewaySupport.errorBody("请求体不是合法 JSON", ResultCode.PARAM_ERROR.getCode()));
    }

    /** G-3 additive：查询参数缺失/类型不匹配 → A0400（此前落 unknown 500，语义归一）。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Object> badParam(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return ResponseEntity.status(400)
                .body(GatewaySupport.errorBody(ResultCode.PARAM_ERROR.getMessage(), ResultCode.PARAM_ERROR.getCode()));
    }

    /** G-3 additive：HTTP method 不匹配 → A0405 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .body(GatewaySupport.errorBody("请求方法不支持", "A0405"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Object> notFound(Exception e) {
        return ResponseEntity.status(404)
                .body(GatewaySupport.errorBody("资源不存在", ResultCode.NOT_FOUND.getCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unknown(Exception e) {
        log.error("网关未预期异常", e);
        return ResponseEntity.status(500)
                .body(GatewaySupport.errorBody("系统异常: " + sanitize(e.getMessage()), ResultCode.SYSTEM_ERROR.getCode()));
    }

    /**
     * G-3 泄漏防护：去掉 URL/连接串/IP:端口/主机:端口与换行（堆栈片段），并截断长度。
     * 保留可读前缀，便于前端展示与脚本断言，但不暴露任何下游拓扑。
     */
    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "内部错误（详见服务端日志与 traceId）";
        }
        String cleaned = message.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(dubbo|http|https|zookeeper|redis|mysql|jdbc|rocketmq)://\\S+", "[redacted]")
                .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b", "[redacted]")
                .replaceAll("[A-Za-z0-9_.-]+:\\d{2,5}\\b", "[redacted]")
                .replaceAll("(?i)(password|secret|token)\\s*[=:]\\s*\\S+", "$1=[redacted]");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
    }
}