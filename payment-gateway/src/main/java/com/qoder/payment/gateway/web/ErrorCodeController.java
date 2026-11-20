package com.qoder.payment.gateway.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-3 错误码表查询（控制台，需登录）：码表单一事实来源 {@link ErrorCodeRegistry}。
 * 既有 A/B/C 码不变，新增子码在 {@code additive} 数组中单列，便于对照审计。
 */
@RestController
@RequestMapping("/api/errors")
@Tag(name = "错误码表 (G-3)", description = "统一错误码/HTTP 映射/分类，additive 子码单列")
public class ErrorCodeController {

    @GetMapping
    @Operation(summary = "统一错误码表", description = "返回全部码表与本次新增（additive）子码；错误体恒为 {error, code, traceId}。")
    public ResponseEntity<Object> codes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorBody", List.of("error", "code", "traceId"));
        result.put("successStyle", "裸数据（不套 envelope）；account.balance 为裸数字 long；Dubbo 内部用 Result 信封");
        result.put("codes", ErrorCodeRegistry.all());
        result.put("additive", ErrorCodeRegistry.additive());
        return ResponseEntity.ok(result);
    }
}