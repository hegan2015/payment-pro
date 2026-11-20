package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.BindCardRequest;
import com.qoder.payment.facade.auth.ConsoleUserDTO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A-2 绑卡管理 REST（P1 C 泳道，新文件）。卡号明文仅请求传输，出参一律掩码（后四位）。
 * 权限：GET=account:read，写=account:write（控制器内校验）。
 * 建议主会话补 RULES：GET /api/accounts/{id}/cards → account:read；
 * POST /api/accounts/{id}/cards、/api/accounts/{id}/cards/{cardId}/default、/api/cards/{cardId}/unbind → account:write。
 */
@RestController
public class CardController {

    @DubboReference
    private AccountFacade accountFacade;

    @PostMapping("/api/accounts/{id}/cards")
    public ResponseEntity<Object> bind(@PathVariable String id, @RequestBody BindCardRequest request) {
        if (!has("account:write")) {
            return forbidden();
        }
        request.setAccountId(id);
        if (request.getOperator() == null) {
            request.setOperator(currentUser());
        }
        return GatewaySupport.of(accountFacade.bindCard(request));
    }

    @GetMapping("/api/accounts/{id}/cards")
    public ResponseEntity<Object> list(@PathVariable String id) {
        if (!has("account:read")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.listCards(id));
    }

    @PostMapping("/api/cards/{cardId}/unbind")
    public ResponseEntity<Object> unbind(@PathVariable String cardId) {
        if (!has("account:write")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.unbindCard(cardId, currentUser()));
    }

    @PostMapping("/api/accounts/{id}/cards/{cardId}/default")
    public ResponseEntity<Object> setDefault(@PathVariable String id, @PathVariable String cardId) {
        if (!has("account:write")) {
            return forbidden();
        }
        return GatewaySupport.of(accountFacade.setDefaultCard(id, cardId));
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