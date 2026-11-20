package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.ExchangeRequest;
import com.qoder.payment.facade.accounting.FxRateSetRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P2 第三期 · C 泳道 REST（A-4 多币种+汇率 / AC-6 多币种账务，<b>独立新增控制器</b>，
 * 不改 ApiController / OpenApiController / LedgerController / AccountExtController）。
 *
 * <p><b>安全</b>：GET 由既有 SecurityConfig 规则 {@code GET /api/accounting/** → accounting:read} 覆盖；
 * POST 落到 {@code /api/** authenticated} 兜底，控制器内再校验权限点。
 * 汇率发布与换汇属<b>资金敏感写操作</b>，强制 {@code X-Step-Up-Token}
 * （复用既有 {@link StepUpTokenStore}，口令再验走会话用户自己的凭据，POST /api/security/step-up 获取）。
 * <b>需主会话补 RULES 映射</b>：POST /api/accounting/fx-rate、/api/accounting/exchange → accounting:exchange；
 * POST /api/accounts/currency → account:write。
 *
 * <p><b>ride 红线</b>：只挂 {@code /api/**}，{@code /openapi/**} 一字未改；
 * {@code account.balance} 仍返回裸数字 long；{@code account.open} 懒开户仍取 CNY BALANCE 户；
 * 手续费/幂等键/BALANCE 同步路径不变。
 * <p><b>凭证只读红线</b>：本控制器无任何修改既有凭证的入口（VoucherGuard 只追加）。
 */
@RestController
public class CurrencyController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    public CurrencyController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- A-4 多币种开户 ----------------

    /** A-4 多币种开户：(memberId,accountType,currency) 幂等；currency 缺省 CNY（与 typed 开户等价）。 */
    @PostMapping("/api/accounts/currency")
    public ResponseEntity<Object> createCurrency(@RequestBody Map<String, Object> body) {
        if (!has("account:write")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.createCurrency(str(body.get("memberId")),
                str(body.get("accountType")), str(body.get("currency"))));
    }

    // ---------------- A-4 汇率 ----------------

    /** 当前生效汇率（version 可选，指定则取该历史版本快照）。 */
    @GetMapping("/api/accounting/fx-rate")
    public ResponseEntity<Object> fxRate(@RequestParam String from, @RequestParam String to,
                                         @RequestParam(required = false) Long version) {
        return GatewaySupport.of(accountingFacade.getFxRate(from, to, version));
    }

    /** 汇率版本历史（倒序）。 */
    @GetMapping("/api/accounting/fx-rates")
    public ResponseEntity<Object> fxRates(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(defaultValue = "20") int limit) {
        return GatewaySupport.of(accountingFacade.listFxRates(from, to, limit));
    }

    /** 发布汇率版本（敏感：需二次认证）。买入/卖出/中间价 + 生效时间 + 来源；同参数重复发布幂等。 */
    @PostMapping("/api/accounting/fx-rate")
    public ResponseEntity<Object> setFxRate(@RequestBody FxRateSetRequest request, HttpServletRequest servletRequest) {
        if (!has("accounting:exchange") && !has("accounting:write")) {
            return forbidden();
        }
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountingFacade.setFxRate(request));
    }

    /** 币种账套目录（CNY 恒首位）。 */
    @GetMapping("/api/accounting/currencies")
    public ResponseEntity<Object> currencies() {
        return GatewaySupport.of(accountingFacade.listCurrencies());
    }

    // ---------------- A-4 换汇 ----------------

    /** 换汇（敏感：需二次认证）。(EXCHANGE,bizNo) 幂等；两张同币种凭证 + 汇兑损益 SYS_FX。 */
    @PostMapping("/api/accounting/exchange")
    public ResponseEntity<Object> exchange(@RequestBody ExchangeRequest request, HttpServletRequest servletRequest) {
        if (!has("accounting:exchange") && !has("accounting:write")) {
            return forbidden();
        }
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountingFacade.exchange(request));
    }

    /** 换汇单查询（含汇率快照与两张凭证号）。 */
    @GetMapping("/api/accounting/exchange/{bizNo}")
    public ResponseEntity<Object> exchange(@PathVariable String bizNo) {
        return GatewaySupport.of(accountingFacade.fxExchange(bizNo));
    }

    // ---------------- AC-6 多币种试算 ----------------

    /**
     * 按币种试算：currency 缺省 = 全账套（顶层仍 CNY 口径 + byCurrency 明细）；
     * 指定则顶层即该币种账套。既有 GET /api/accounting/trial 一字未改。
     */
    @GetMapping("/api/accounting/trial/by-currency")
    public ResponseEntity<Object> trialByCurrency(@RequestParam(required = false) String currency) {
        return GatewaySupport.of(accountingFacade.trial(currency));
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

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
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