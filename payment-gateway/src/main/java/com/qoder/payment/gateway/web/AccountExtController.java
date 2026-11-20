package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.FreezeRequest;
import com.qoder.payment.facade.account.StatusChangeRequest;
import com.qoder.payment.facade.account.UnfreezeRequest;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
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
 * A-1 类型开户 / A-3 冻结细分 / M-3 分级限额 REST（P1 C 泳道，新文件不改 ApiController）。
 * 权限：读=account:read，写=account:write（控制器内校验；未登录由既有安全链 401）。
 * 建议主会话补 RULES：POST /api/accounts/typed、/api/accounts/{id}/status-ex、
 * /api/accounts/{id}/freeze、/api/accounts/{id}/unfreeze、/api/accounts/{id}/level/upgrade → account:write；
 * GET /api/accounts/{id}/level、/api/accounts/{id}/freezes、/api/accounts/{id}/limit-check → account:read。
 */
@RestController
public class AccountExtController {

    @DubboReference
    private AccountFacade accountFacade;

    /** A-1 类型开户（缺省 BALANCE 兼容）；(memberId,accountType) 幂等。 */
    @PostMapping("/api/accounts/typed")
    public ResponseEntity<Object> createTyped(@RequestBody Map<String, Object> body) {
        if (!has("account:write")) {
            return forbidden();
        }
        String memberId = str(body.get("memberId"));
        String accountType = str(body.get("accountType"));
        return GatewaySupport.of(accountFacade.createTyped(memberId, accountType));
    }

    /** A-3 状态冻结细分（FULL/JUDICIAL；JUDICIAL 解冻需 authorizedBy）。 */
    @PostMapping("/api/accounts/{id}/status-ex")
    public ResponseEntity<Object> statusEx(@PathVariable String id, @RequestBody StatusChangeRequest request) {
        if (!has("account:write")) {
            return forbidden();
        }
        request.setAccountId(id);
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountFacade.updateStatusEx(request));
    }

    /** A-3 金额冻结（RISK/JUDICIAL/PREAUTH/DISPUTE，bizNo 幂等）。 */
    @PostMapping("/api/accounts/{id}/freeze")
    public ResponseEntity<Object> freeze(@PathVariable String id, @RequestBody FreezeRequest request) {
        if (!has("account:write")) {
            return forbidden();
        }
        request.setAccountId(id);
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountFacade.freezeAmount(request));
    }

    /** A-3 解冻（须对应原因；JUDICIAL 双人复核）。 */
    @PostMapping("/api/accounts/{id}/unfreeze")
    public ResponseEntity<Object> unfreeze(@PathVariable String id, @RequestBody UnfreezeRequest request) {
        if (!has("account:write")) {
            return forbidden();
        }
        if (request.getAccountId() == null) {
            request.setAccountId(id);
        }
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountFacade.unfreezeAmount(request));
    }

    @GetMapping("/api/accounts/{id}/freezes")
    public ResponseEntity<Object> freezes(@PathVariable String id, @RequestParam(required = false) String status) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.listFreezes(id, status));
    }

    /** M-3 等级与限额。 */
    @GetMapping("/api/accounts/{id}/level")
    public ResponseEntity<Object> level(@PathVariable String id) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.getLevel(id));
    }

    @PostMapping("/api/accounts/{id}/level/upgrade")
    public ResponseEntity<Object> upgrade(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!has("account:write")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.upgradeLevel(id, str(body.get("targetLevel")), currentUser()));
    }

    @GetMapping("/api/accounts/{id}/limit-check")
    public ResponseEntity<Object> limitCheck(@PathVariable String id, @RequestParam long amount,
                                             @RequestParam(defaultValue = "SINGLE") String kind) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.checkLimit(id, amount, kind));
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