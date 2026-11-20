package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.auth.ConsoleUserDTO;
import com.qoder.payment.facade.member.ApplicationSubmitRequest;
import com.qoder.payment.facade.member.MemberFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M-4 商户进件 + X-2 审核流 REST（P1 C 泳道，新文件）。
 * 权限：读=member:read；提交/重提/签约/绑卡=member:write；
 * 审核 approve/reject/supplement=OPERATOR+（现以 member:write 校验，reviewer 恒取会话用户防伪造）。
 * 建议主会话补 RULES：/api/merchant/review/** → merchant:review（新权限点，OPERATOR+ 授予），
 * 其余 /api/merchant/** → member:read/member:write。
 */
@RestController
@RequestMapping("/api/merchant")
public class OnboardingController {

    @DubboReference
    private MemberFacade memberFacade;

    @PostMapping("/applications")
    public ResponseEntity<Object> submit(@RequestBody ApplicationSubmitRequest request) {
        if (!has("member:write")) {
            return forbidden();
        }
        request.setSubmitter(currentUser());
        return GatewaySupport.of(memberFacade.submitApplication(request));
    }

    @GetMapping("/applications")
    public ResponseEntity<Object> list(@RequestParam(required = false) String status) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.listApplications(status));
    }

    @GetMapping("/applications/{id}")
    public ResponseEntity<Object> get(@PathVariable String id) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.getApplication(id));
    }

    @PostMapping("/applications/{id}/resubmit")
    public ResponseEntity<Object> resubmit(@PathVariable String id, @RequestBody ApplicationSubmitRequest request) {
        if (!has("member:write")) {
            return forbidden();
        }
        request.setSubmitter(currentUser());
        return GatewaySupport.of(memberFacade.resubmitApplication(id, request));
    }

    @GetMapping("/review")
    public ResponseEntity<Object> reviewTasks(@RequestParam(required = false) String status) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.listReviewTasks(status));
    }

    @PostMapping("/review/{applicationId}/approve")
    public ResponseEntity<Object> approve(@PathVariable String applicationId, @RequestBody(required = false) Map<String, Object> body) {
        if (!has("member:write")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.approveApplication(applicationId, currentUser(), str(body, "opinion")));
    }

    @PostMapping("/review/{applicationId}/reject")
    public ResponseEntity<Object> reject(@PathVariable String applicationId, @RequestBody Map<String, Object> body) {
        if (!has("member:write")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.rejectApplication(applicationId, currentUser(), str(body, "reason")));
    }

    @PostMapping("/review/{applicationId}/supplement")
    public ResponseEntity<Object> supplement(@PathVariable String applicationId, @RequestBody Map<String, Object> body) {
        if (!has("member:write")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.supplementApplication(applicationId, currentUser(), str(body, "opinion")));
    }

    @PostMapping("/contracts")
    public ResponseEntity<Object> signContract(@RequestBody Map<String, Object> body) {
        if (!has("member:write")) {
            return forbidden();
        }
        Integer feeBps = body.get("feeBps") == null ? null : Integer.valueOf(body.get("feeBps").toString());
        return GatewaySupport.of(memberFacade.signContract(str(body, "applicationId"), str(body, "products"),
                feeBps, currentUser()));
    }

    /** UI additive（只读）：合同列表，merchantId 可选过滤（管理台「合同与结算卡」页）。 */
    @GetMapping("/contracts")
    public ResponseEntity<Object> listContracts(@RequestParam(required = false) String merchantId) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.listContracts(merchantId));
    }

    /** UI additive（只读）：合同详情（抽屉联查进件单 / 商户 / 结算卡）。 */
    @GetMapping("/contracts/{id}")
    public ResponseEntity<Object> getContract(@PathVariable String id) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.getContract(id));
    }

    @PostMapping("/settle-cards")
    public ResponseEntity<Object> bindSettleCard(@RequestBody Map<String, Object> body) {
        if (!has("member:write")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.bindSettleCard(str(body, "applicationId"), str(body, "cardNo"),
                str(body, "bankName"), currentUser()));
    }

    @GetMapping("/settle-cards")
    public ResponseEntity<Object> listSettleCards(@RequestParam String merchantId) {
        if (!has("member:read")) {
            return forbidden();
        }
        return GatewaySupport.of(memberFacade.listSettleCards(merchantId));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
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