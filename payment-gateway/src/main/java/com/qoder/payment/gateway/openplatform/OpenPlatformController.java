package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.openplatform.ApiBillDTO;
import com.qoder.payment.facade.openplatform.ApiQuotaDTO;
import com.qoder.payment.facade.openplatform.OpenAppDTO;
import com.qoder.payment.facade.openplatform.OpenAppRegisterRequest;
import com.qoder.payment.facade.openplatform.OpenAppUpdateRequest;
import com.qoder.payment.facade.openplatform.OpenApiScopes;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import com.qoder.payment.facade.openplatform.SandboxAccountDTO;
import com.qoder.payment.gateway.security.AuditSupport;
import com.qoder.payment.gateway.web.GatewaySupport;
import com.qoder.payment.gateway.web.MerchantCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * G-4a 开放平台控制台（/api/openplatform/**，走既有 Bearer + RBAC；权限点复用 merchant:read/write）。
 *
 * <p>应用注册/授权 scope/配额/单价/撤销 + 配额视图 + 日账单 + 沙箱账户只读。
 * 撤销/改策略后<b>同步失效</b>网关策略缓存、商户凭证缓存与 access_token → 即时生效。
 *
 * <p>E-1 延续：list/get 出参 appSecret 恒为 null（沙箱明文只在注册响应返回一次）。
 */
@Slf4j
@RestController
@RequestMapping("/api/openplatform")
@Tag(name = "开放平台管理 (G-4a)", description = "应用注册/授权 scope/沙箱/配额/调用计费账单")
public class OpenPlatformController {

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    @Autowired
    private AppPolicyCache policyCache;

    @Autowired
    private MerchantCache merchantCache;

    @Autowired
    private OpenApiAuthService authService;

    @Autowired
    private ApiBillingService billing;

    @Autowired
    private ApiQuotaGuard quotaGuard;

    @PostMapping("/apps")
    @Operation(summary = "注册开放平台应用（sandbox=true 生成独立 appId/密钥，明文仅本次返回）")
    public ResponseEntity<Object> register(@RequestBody OpenAppRegisterRequest request) {
        request.setOperator(AuditSupport.currentUsername());
        Result<OpenAppDTO> result = openPlatformFacade.registerApp(request);
        if (result.isSuccess()) {
            policyCache.evict(result.getData().getAppId());
        }
        return GatewaySupport.of(result);
    }

    @GetMapping("/apps")
    @Operation(summary = "应用列表（脱敏）")
    public ResponseEntity<Object> list() {
        return GatewaySupport.of(openPlatformFacade.listApps());
    }

    @GetMapping("/apps/{appId}")
    @Operation(summary = "应用详情（脱敏）")
    public ResponseEntity<Object> get(@PathVariable String appId) {
        return GatewaySupport.of(openPlatformFacade.getApp(appId));
    }

    @PutMapping("/apps/{appId}")
    @Operation(summary = "调整授权 scope/限流/日配额/计费单价/默认版本（null 字段不改）")
    public ResponseEntity<Object> update(@PathVariable String appId, @RequestBody OpenAppUpdateRequest request) {
        request.setAppId(appId);
        request.setOperator(AuditSupport.currentUsername());
        Result<OpenAppDTO> result = openPlatformFacade.updateApp(request);
        if (result.isSuccess()) {
            policyCache.evict(appId);
        }
        return GatewaySupport.of(result);
    }

    @PostMapping("/apps/{appId}/revoke")
    @Operation(summary = "撤销授权（即时生效：策略缓存 + 凭证缓存 + access_token 同步失效）")
    public ResponseEntity<Object> revoke(@PathVariable String appId) {
        Result<OpenAppDTO> result = openPlatformFacade.revokeApp(appId, AuditSupport.currentUsername());
        if (result.isSuccess()) {
            invalidate(appId);
        }
        return GatewaySupport.of(result);
    }

    @PostMapping("/apps/{appId}/activate")
    @Operation(summary = "恢复授权")
    public ResponseEntity<Object> activate(@PathVariable String appId) {
        Result<OpenAppDTO> result = openPlatformFacade.activateApp(appId, AuditSupport.currentUsername());
        if (result.isSuccess()) {
            invalidate(appId);
        }
        return GatewaySupport.of(result);
    }

    @GetMapping("/quota")
    @Operation(summary = "配额视图：上限/当日已用/剩余/实时 QPS（计费账本 + 网关计数器）")
    public ResponseEntity<Object> quota(@RequestParam String appId) {
        billing.flushQuietly();
        Result<ApiQuotaDTO> result = openPlatformFacade.getQuota(appId);
        if (result.isSuccess()) {
            result.getData().setQpsUsed(quotaGuard.qpsUsed(appId));
        }
        return GatewaySupport.of(result);
    }

    @GetMapping("/bill")
    @Operation(summary = "调用计费日账单（appId 可选=全部；bizDate 可选=当日 yyyyMMdd）")
    public ResponseEntity<Object> bill(@RequestParam(required = false) String appId,
                                       @RequestParam(required = false) String bizDate) {
        billing.flushQuietly();
        Result<List<ApiBillDTO>> result = openPlatformFacade.getBill(appId, bizDate);
        return GatewaySupport.of(result);
    }

    @GetMapping("/scopes")
    @Operation(summary = "scope 目录（API → scope 映射）")
    public ResponseEntity<Object> scopes() {
        return GatewaySupport.of(Result.ok(OpenApiScopes.catalog()));
    }

    @GetMapping("/sandbox/{appId}/accounts")
    @Operation(summary = "沙箱账户列表（只读，独立账本，与生产账户无关）")
    public ResponseEntity<Object> sandboxAccounts(@PathVariable String appId) {
        Result<List<SandboxAccountDTO>> result = openPlatformFacade.sandboxListAccounts(appId);
        return GatewaySupport.of(result);
    }

    @GetMapping("/gate")
    @Operation(summary = "闸门自述：作用域/免闸门路径/默认策略（便于运维核对 ride 兼容性）")
    public ResponseEntity<Object> gate() {
        return GatewaySupport.of(Result.ok(Map.of(
                "scope", "/openapi/*",
                "exempt", OpenPlatformFilter.exemptPaths(),
                "unregisteredPolicy", "全放行 + 免费计数（ride-pro 兼容红线）",
                "rejectBeforeAcceptance", true,
                "billingTouchesFundLedger", false)));
    }

    private void invalidate(String appId) {
        policyCache.evict(appId);
        merchantCache.evict(appId);
        authService.revokeByApp(appId);
    }
}