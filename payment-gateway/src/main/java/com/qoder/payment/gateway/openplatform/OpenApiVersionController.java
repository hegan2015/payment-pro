package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenApiScopes;
import com.qoder.payment.gateway.web.GatewaySupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * G-4b 版本协商/弃用公告公开端点（免签名免 Bearer，供 SDK 启动时协商）。
 *
 * <p>{@code GET /openapi/version?appId=xxx} 额外回显该应用的默认版本（按 appId 灰度切 v2 的协商入口）；
 * 不传 appId 只返回平台版本公告，<b>不泄露任何应用密钥/策略明细</b>。
 */
@RestController
@RequestMapping("/openapi")
@Tag(name = "开放平台版本 (G-4b)", description = "版本协商/弃用公告/scope 目录；v1 冻结，v2 additive")
public class OpenApiVersionController {

    @Autowired
    private ApiVersionRouter versionRouter;

    @Autowired
    private AppPolicyCache policyCache;

    @GetMapping("/version")
    @Operation(summary = "版本公告与协商规则（v1 冻结方法清单 + v2 additive 清单 + 签名口径）")
    public ResponseEntity<Object> version(@RequestParam(required = false) String appId) {
        String appDefault = null;
        if (appId != null && !appId.isBlank()) {
            AppPolicyDTO policy = policyCache.resolve(appId);
            appDefault = policy.getApiVersion();
        }
        return GatewaySupport.of(Result.ok(versionRouter.announcement(appDefault)));
    }

    @GetMapping("/scopes")
    @Operation(summary = "scope 目录（API → scope）")
    public ResponseEntity<Object> scopes() {
        return GatewaySupport.of(Result.ok(OpenApiScopes.catalog()));
    }
}