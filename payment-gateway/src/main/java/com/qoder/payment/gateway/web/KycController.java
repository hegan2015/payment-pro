package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.member.KycRequest;
import com.qoder.payment.facade.member.KycResultDTO;
import com.qoder.payment.facade.member.MemberFacade;
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
 * M-2 用户实名 KYC REST（P1 C 泳道，新文件不改既有控制器）。
 * 权限：POST=member:write、GET=member:read（控制器内校验，ADMIN 通配 *；
 * 未登录由既有安全链 401 兜底）。敏感要素脱敏出参（哈希落库在 member 域）。
 * 建议主会话在 SecurityConfig RULES 补：POST /api/members/{id}/kyc → member:write。
 */
@RestController
public class KycController {

    @DubboReference
    private MemberFacade memberFacade;

    @PostMapping("/api/members/{id}/kyc")
    public ResponseEntity<Object> submit(@PathVariable String id, @RequestBody KycRequest request) {
        if (!has("member:write")) {
            return forbidden();
        }
        request.setMemberId(id);
        Result<KycResultDTO> result = memberFacade.submitKyc(request);
        return GatewaySupport.of(result);
    }

    @GetMapping("/api/members/{id}/kyc")
    public ResponseEntity<Object> get(@PathVariable String id) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.getKyc(id));
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