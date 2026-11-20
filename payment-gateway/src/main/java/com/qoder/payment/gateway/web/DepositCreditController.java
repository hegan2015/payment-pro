package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.facade.settle.AdvanceRequest;
import com.qoder.payment.facade.settle.CreditAccountDTO;
import com.qoder.payment.facade.settle.DepositRequest;
import com.qoder.payment.facade.settle.SettleFacade;
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
 * P2 第三期 · G 泳道 ST-6 REST（保证金/授信垫资，<b>独立新增控制器</b>，
 * 不改 SettleOpsController / ApiController / OpenApiController / static）。
 *
 * <p><b>安全</b>：既有 SecurityConfig 规则 {@code GET /api/settle/** → settle:read}、
 * {@code POST /api/settle/** → settle:write} 天然覆盖；<b>释放/扣划/授信/追偿属敏感操作，
 * 控制器内强制 X-Step-Up-Token</b>（复用既有 StepUpTokenStore）。operator 恒取会话用户。
 *
 * <p><b>资金红线</b>：全部动账经 settle 域 → 既有 AccountingFacade.post 复式记账 + IdempotentTemplate；
 * 本控制器不含任何自建账本/直改余额入口。
 * <p><b>ride 红线</b>：只挂 /api/**，/openapi/** 与既有结算/提现/手续费路径一字未改。
 */
@RestController
public class DepositCreditController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private SettleFacade settleFacade;

    public DepositCreditController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- ST-6 保证金 ----------------

    /** 缴存保证金：D 出资账户 / C SYS_DEPOSIT。(DEPOSIT_FREEZE,requestId) 幂等。 */
    @PostMapping("/api/settle/deposit")
    public ResponseEntity<Object> deposit(@RequestBody DepositRequest request) {
        request.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.deposit(request));
    }

    /** 释放保证金（敏感：需二次认证）。D SYS_DEPOSIT / C 原出资账户。 */
    @PostMapping("/api/settle/deposit/release")
    public ResponseEntity<Object> release(@RequestBody DepositRequest request, HttpServletRequest servletRequest) {
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        request.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.releaseDeposit(request));
    }

    /** 扣划保证金（敏感：需二次认证）。D SYS_DEPOSIT / C 平台收入户或 SYS_ADVANCE（偿还垫资）。 */
    @PostMapping("/api/settle/deposit/deduct")
    public ResponseEntity<Object> deduct(@RequestBody DepositRequest request, HttpServletRequest servletRequest) {
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        request.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.deductDeposit(request));
    }

    @GetMapping("/api/settle/deposits")
    public ResponseEntity<Object> deposits(@RequestParam(required = false) String merchantId,
                                           @RequestParam(required = false) String type) {
        return GatewaySupport.of(settleFacade.listDeposits(merchantId, type));
    }

    @GetMapping("/api/settle/deposits/{id}")
    public ResponseEntity<Object> deposit(@PathVariable String id) {
        return GatewaySupport.of(settleFacade.getDeposit(id));
    }

    /** 保证金守恒视图（SYS_DEPOSIT 余额 == 未释放保证金）。 */
    @GetMapping("/api/settle/deposit/summary")
    public ResponseEntity<Object> depositSummary(@RequestParam(required = false) String merchantId) {
        return GatewaySupport.of(settleFacade.depositSummary(merchantId));
    }

    // ---------------- ST-6 授信/垫资 ----------------

    /** 授信额度维护（敏感：需二次认证）。额度不得低于已占用。 */
    @PostMapping("/api/settle/credit")
    public ResponseEntity<Object> grantCredit(@RequestBody CreditAccountDTO credit, HttpServletRequest servletRequest) {
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        credit.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.grantCredit(credit));
    }

    @GetMapping("/api/settle/credit")
    public ResponseEntity<Object> credit(@RequestParam String merchantId) {
        return GatewaySupport.of(settleFacade.getCredit(merchantId));
    }

    /** 授信/垫资守恒视图（used == 未回收 == −balance(SYS_ADVANCE)）。 */
    @GetMapping("/api/settle/credit/summary")
    public ResponseEntity<Object> creditSummary(@RequestParam String merchantId) {
        return GatewaySupport.of(settleFacade.creditSummary(merchantId));
    }

    /** 垫资：额度内原子扣减 + D SYS_ADVANCE / C 商户账户。(ADVANCE,requestId) 幂等。 */
    @PostMapping("/api/settle/advance")
    public ResponseEntity<Object> advance(@RequestBody AdvanceRequest request) {
        request.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.advancePay(request));
    }

    /** 垫资追偿（敏感：需二次认证）。D 商户账户 / C SYS_ADVANCE + 额度释放。 */
    @PostMapping("/api/settle/advance/recover")
    public ResponseEntity<Object> recover(@RequestBody AdvanceRequest request, HttpServletRequest servletRequest) {
        ResponseEntity<Object> denied = requireStepUp(servletRequest);
        if (denied != null) {
            return denied;
        }
        request.setOperator(currentUser());
        return GatewaySupport.of(settleFacade.recoverAdvance(request));
    }

    @GetMapping("/api/settle/advances")
    public ResponseEntity<Object> advances(@RequestParam(required = false) String merchantId,
                                           @RequestParam(required = false) String type) {
        return GatewaySupport.of(settleFacade.listAdvances(merchantId, type));
    }

    @GetMapping("/api/settle/advances/{id}")
    public ResponseEntity<Object> advance(@PathVariable String id) {
        return GatewaySupport.of(settleFacade.getAdvance(id));
    }

    /** A-5 销户护栏用资金敞口（只读）。 */
    @GetMapping("/api/settle/exposure")
    public ResponseEntity<Object> exposure(@RequestParam String memberId,
                                           @RequestParam(required = false) String accountId) {
        return GatewaySupport.of(settleFacade.fundExposure(memberId, accountId));
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
}