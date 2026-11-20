package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.payment.ChallengeContinueRequest;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.ShareBackRequest;
import com.qoder.payment.facade.payment.ShareRequest;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P1-I 支付编排工作流控制台 REST（<b>新增独立控制器，不改网关既有文件</b>）。
 *
 * <p>薄适配层：业务经 Dubbo 路由到 payment 域（P-2 组合支付经既有 /api/cashier/pay 携 legs；
 * P-3 分账 / P-4 退款查询补单 / P-5 关闭撤销 / CHALLENGE 续付在此暴露）。
 *
 * <p>鉴权（与 ChannelOpsController/RiskOpsController 同模式，不改 SecurityConfig）：
 * 读端点登录即可（/api/** authenticated）；资金动账写端点（关闭/撤销/分账/回退/执行）
 * 校验既有权限点 {@code payment:refund}（或 ADMIN 通配 {@code *}）+ 二次认证
 * X-Step-Up-Token（归属须与会话一致）；退款补单校验 {@code payment:reconverge}；
 * CHALLENGE 续付校验 {@code payment:pay}（与 /api/cashier/pay 同级）。
 * 闸门只作用 /api/**，/openapi/** 冻结调用面不受影响（§0.7 红线）。
 *
 * <p>新权限点（payment:close/payment:share）与 OpenAPI trade.share/trade.close 等
 * 冻结面之外的商户端点扩展，由主会话统一接线（本轮只复用既有权限点）。
 */
@RestController
@RequestMapping("/api")
public class PaymentOpsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentOpsController.class);

    @DubboReference(check = false, timeout = 15000, retries = 0)
    private PaymentFacade paymentFacade;

    private final StepUpTokenStore stepUpTokenStore;

    public PaymentOpsController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- P-5 关闭/撤销 ----------------

    @PostMapping("/payments/{id}/close")
    public ResponseEntity<Object> close(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund", "payment:pay");
        return deny != null ? deny
                : GatewaySupport.of(paymentFacade.close(id, str(body, "requestId")));
    }

    @PostMapping("/payments/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        return deny != null ? deny
                : GatewaySupport.of(paymentFacade.cancel(id, str(body, "requestId")));
    }

    /** P-5 关闭竞态悬挂修复（存量兜底，幂等；受理侧防悬挂守卫已根治新增路径）。 */
    @PostMapping("/payments/{id}/race-repair")
    public ResponseEntity<Object> raceRepair(@PathVariable String id,
                                             @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        return deny != null ? deny : GatewaySupport.of(paymentFacade.repairCloseRace(id));
    }

    /**
     * P360 受控终态修复：dtx=CONFIRMED 且凭证齐全的历史竞态脏单 FAILED→SUCCESS，只翻状态不动资金。
     * 二次认证 + 大额双人复核由 StepUpAuthInterceptor 闸门承担；操作人恒取会话用户。
     */
    @PostMapping("/payments/{id}/repair-terminal")
    public ResponseEntity<Object> repairTerminal(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        if (deny != null) {
            return deny;
        }
        return GatewaySupport.of(paymentFacade.repairTerminal(
                id, str(body, "reason"), str(body, "requestId"),
                com.qoder.payment.gateway.security.AuditSupport.currentUsername()));
    }

    // ---------------- P-3 分账编排 ----------------

    @PostMapping("/payments/{id}/share")
    public ResponseEntity<Object> share(@PathVariable String id, @RequestBody ShareRequest request,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        if (deny != null) {
            return deny;
        }
        request.setPaymentId(id);
        return GatewaySupport.of(paymentFacade.share(request));
    }

    @GetMapping("/payments/{id}/shares")
    public ResponseEntity<Object> shares(@PathVariable String id) {
        return GatewaySupport.of(paymentFacade.listShares(id));
    }

    @GetMapping("/shares/{id}")
    public ResponseEntity<Object> share(@PathVariable String id) {
        return GatewaySupport.of(paymentFacade.getShare(id));
    }

    @PostMapping("/shares/{id}/back")
    public ResponseEntity<Object> shareBack(@PathVariable String id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        if (deny != null) {
            return deny;
        }
        ShareBackRequest request = new ShareBackRequest();
        request.setShareId(id);
        request.setRequestId(str(body, "requestId"));
        return GatewaySupport.of(paymentFacade.shareBack(request));
    }

    /** 手动/补单驱动分账执行（幂等，outbox DEAD 后的运维兜底入口）。 */
    @PostMapping("/shares/{id}/execute")
    public ResponseEntity<Object> executeShare(@PathVariable String id,
                                               @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token, "payment:refund");
        return deny != null ? deny : GatewaySupport.of(paymentFacade.executeShare(id));
    }

    // ---------------- P-4 退款查询/补单 ----------------

    @GetMapping("/refunds/{id}")
    public ResponseEntity<Object> refund(@PathVariable String id) {
        return GatewaySupport.of(paymentFacade.getRefund(id));
    }

    @PostMapping("/refunds/{id}/reconverge")
    public ResponseEntity<Object> refundReconverge(@PathVariable String id) {
        if (!hasAnyPermission("payment:reconverge", "payment:refund")) {
            return forbidden();
        }
        return GatewaySupport.of(paymentFacade.refundReconverge(id));
    }

    // ---------------- CHALLENGE 续付（R-4 × C-3） ----------------

    @PostMapping("/payments/challenge/continue")
    public ResponseEntity<Object> continueChallenge(@RequestBody ChallengeContinueRequest request) {
        if (!hasAnyPermission("payment:pay")) {
            return forbidden();
        }
        return GatewaySupport.of(paymentFacade.continueChallenge(request));
    }

    // ---------------- 内部 ----------------

    private ResponseEntity<Object> guard(String stepUpToken, String... permissions) {
        if (!hasAnyPermission(permissions)) {
            return forbidden();
        }
        String user = sessionUser();
        String owner = stepUpTokenStore.validate(stepUpToken);
        if (owner == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "敏感操作需二次认证（X-Step-Up-Token 缺失或已失效）", "code", "A0403"));
        }
        if (!owner.equals(user)) {
            log.warn("支付编排敏感操作拒绝：step-up token 归属与会话不符 session={} owner={}", user, owner);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "二次认证凭据不属于当前登录账号", "code", "A0403"));
        }
        return null;
    }

    private static boolean hasAnyPermission(String... permissions) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if ("*".equals(value)) {
                return true;
            }
            for (String permission : permissions) {
                if (permission.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "无权限执行该操作", "code", "A0403"));
    }

    private static String sessionUser() {
        return com.qoder.payment.gateway.security.AuditSupport.currentUsername();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}