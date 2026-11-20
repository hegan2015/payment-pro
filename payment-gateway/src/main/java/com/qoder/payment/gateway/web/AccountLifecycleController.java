package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.account.AccountCloseRequest;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.LevelChangeRequest;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * P2 第三期 · G 泳道 A-5 REST（开销户/升降级，<b>独立新增控制器</b>，
 * 不改 ApiController / AccountExtController / CurrencyController / OpenApiController / static）。
 *
 * <p><b>安全</b>：POST 落到既有 SecurityConfig 的 {@code /api/** authenticated} 兜底，
 * 控制器内再校验 {@code account:write}；<b>销户与降级属敏感操作，强制 X-Step-Up-Token</b>
 * （复用既有 {@link StepUpTokenStore}，口令再验走会话用户自己的凭据，POST /api/security/step-up 获取）。
 * operator 恒取会话用户，不接受 body 冒充。
 * <p><b>需主会话补 RULES</b>：POST /api/accounts/&#42;/close、/api/accounts/&#42;/level → account:write；
 * GET /api/accounts/&#42;/lifecycle-logs → account:read（当前由控制器内权限点校验兜底）。
 *
 * <p><b>ride 红线</b>：只挂 /api/**，/openapi/** 一字未改；account.open 懒开户幂等、
 * account.balance 裸 long、BALANCE 同步、手续费口径全部不变；本控制器无任何批量/自动销户入口，
 * ride 既有账户不会被销户或降级。
 */
@RestController
public class AccountLifecycleController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";
    private static final List<String> LEVELS = List.of("I", "II", "III");

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private AccountFacade accountFacade;

    public AccountLifecycleController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    /**
     * A-5 销户（敏感：需二次认证）。逻辑销户不物理删；余额非 0 / 有冻结 / 有在途一律拒绝。
     * (ACCOUNT_CLOSE,accountId) 幂等：已 CLOSED 重复调用返回既有快照。
     */
    @PostMapping("/api/accounts/{id}/close")
    public ResponseEntity<Object> close(@PathVariable String id, @RequestBody AccountCloseRequest request,
                                        HttpServletRequest servletRequest) {
        if (!has("account:write")) {
            return forbidden();
        }
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        request.setAccountId(id);
        request.setOperator(currentUser());
        return GatewaySupport.of(accountFacade.closeAccount(request));
    }

    /**
     * A-5 等级升降级（M-3 限额 + KYC 联动）。<b>降级敏感：需二次认证</b>；
     * 升级沿用 M-3 既有闸门（KYC 四要素 / III 类 operator 复核），不额外加 step-up（与既有 upgrade 端点一致）。
     */
    @PostMapping("/api/accounts/{id}/level")
    public ResponseEntity<Object> changeLevel(@PathVariable String id, @RequestBody LevelChangeRequest request,
                                              HttpServletRequest servletRequest) {
        if (!has("account:write")) {
            return forbidden();
        }
        String target = request.getTargetLevel() == null ? "" : request.getTargetLevel().trim().toUpperCase();
        if (!LEVELS.contains(target)) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetLevel 仅支持 I/II/III", "code", "A0400"));
        }
        AccountDTO account = accountFacade.get(id).orThrow();
        String current = account.getLevel() == null || account.getLevel().isBlank() ? "I" : account.getLevel();
        if (LEVELS.indexOf(target) < LEVELS.indexOf(current)) {
            ResponseEntity<Object> denied = requireStepUp(servletRequest);
            if (denied != null) {
                return denied;
            }
        }
        request.setAccountId(id);
        request.setOperator(currentUser());
        return GatewaySupport.of(accountFacade.changeLevel(request));
    }

    /** A-5 生命周期留痕（只读；销户/升降级全程审计）。 */
    @GetMapping("/api/accounts/{id}/lifecycle-logs")
    public ResponseEntity<Object> logs(@PathVariable String id) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.listLifecycleLogs(id));
    }

    /**
     * A-5 additive：单账户快照（含 lifecycleStatus/closedAt）。
     * 既有 {@code GET /api/accounts?memberId=} 列表口径一字未改；本端点只补单户只读视图。
     */
    @GetMapping("/api/accounts/{id}")
    public ResponseEntity<Object> account(@PathVariable String id) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.get(id));
    }

    // ------------------------------------------------------------------ 工具

    private ResponseEntity<Object> requireStepUp(HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (operator == null) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                    "code", "A0403"));
        }
        return null;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ConsoleUserDTO user ? user.getUsername() : "anonymous";
    }

    private static boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()) || "*".equals(a.getAuthority()));
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "无权限执行该操作", "code", "A0403"));
    }
}