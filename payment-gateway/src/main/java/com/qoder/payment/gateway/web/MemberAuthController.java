package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.facade.member.MemberLoginRequest;
import com.qoder.payment.facade.member.MemberRegisterRequest;
import com.qoder.payment.facade.member.MemberFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M-5 C 端用户/商户登录认证 REST（P1 C 泳道，新文件；与控制台 /api/auth/** 完全独立，语义互不影响）。
 * <p><b>安全链现状</b>：/api/member/** 落既有 /api/** 兜底（需控制台 Bearer）。
 * C 端自助放行需主会话在 SecurityConfig 增 permitAll：
 * /api/member/register、/api/member/login、/api/member/sms-code、/api/member/logout、/api/member/session，
 * 并为 /api/member/password 增会员 token 校验（X-Member-Token → MemberFacade.getMemberSession）。
 * 在此之前本端点以控制台登录态驱动（沙箱/运营代客模式），功能语义完整可验。
 * <p>口令/验证码明文不落网关日志（AuditFilter 只记路径/结果）；短信验证码沙箱返回仅供联调。
 */
@RestController
@RequestMapping("/api/member")
public class MemberAuthController {

    @DubboReference
    private MemberFacade memberFacade;

    @PostMapping("/register")
    public ResponseEntity<Object> register(@RequestBody MemberRegisterRequest request) {
        return GatewaySupport.of(memberFacade.memberRegister(request));
    }

    @PostMapping("/sms-code")
    public ResponseEntity<Object> smsCode(@RequestBody Map<String, Object> body) {
        String mobile = str(body.get("mobile"));
        var result = memberFacade.sendSmsCode(mobile);
        if (!result.isSuccess()) {
            return GatewaySupport.of(result);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mobile", mobile == null ? null : mobile.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"));
        data.put("sandboxCode", result.getData());
        data.put("expiresIn", 300);
        data.put("note", "沙箱验证码仅供联调，真实短信通道后续接入");
        return ResponseEntity.ok(data);
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody MemberLoginRequest request) {
        return GatewaySupport.of(memberFacade.memberLogin(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(memberFacade.memberLogout(str(body.get("token"))));
    }

    @GetMapping("/session")
    public ResponseEntity<Object> session(@RequestParam String token) {
        return GatewaySupport.of(memberFacade.getMemberSession(token));
    }

    /** 改密：校验旧口令（member 域）+ 审计；成功后全部会员会话失效。 */
    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(memberFacade.changePassword(str(body.get("memberId")),
                str(body.get("oldPassword")), str(body.get("newPassword")), currentUser()));
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ConsoleUserDTO user ? user.getUsername() : "anonymous";
    }
}