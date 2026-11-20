package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.marketing.BestPromotionRequest;
import com.qoder.payment.facade.marketing.MarketingFacade;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PaymentFacade;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P2-E 营销抵扣编排（P-7）+ 最优组合计算（C-4）控制台 REST —— <b>新增独立控制器，不改网关任何既有文件</b>。
 *
 * <p>薄适配层：C-4 经 Dubbo 路由到 marketing 域（20893），P-7 经既有 {@code PaymentFacade.pay}
 * 携 additive 的 {@code marketing} 字段路由到 payment 域；返回裸数据（与既有控制台风格一致）。
 *
 * <p><b>鉴权</b>（与 PaymentOpsController/MarketingController 同模式，不改 SecurityConfig）：
 * SecurityConfig 兜底 {@code /api/** authenticated} 天然覆盖；读端点校验既有权限点
 * {@code marketing:read}（或 ADMIN 通配 {@code *}），资金动账写端点（pay/rollback/realloc）
 * 校验 {@code payment:pay}/{@code payment:refund}/{@code marketing:write} + 二次认证
 * {@code X-Step-Up-Token}（归属须与会话一致）。
 *
 * <p><b>ride 红线</b>：只挂 {@code /api/marketing-pay/**}，{@code /openapi/**} 一字未改——
 * 8 个冻结方法、BALANCE 同步语义、feeBps→SYS_FEE 手续费口径、notify http 推送、
 * cashier OpenAPI 9 方法返回结构均不受影响。营销域未启动时本控制器返回明确业务错误
 * （营销域不可达），不影响其它 13 进程与 13/13 健康断言。
 */
@RestController
@RequestMapping("/api/marketing-pay")
public class MarketingPayController {

    private static final Logger log = LoggerFactory.getLogger(MarketingPayController.class);
    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    @DubboReference(check = false, timeout = 20000, retries = 0)
    private PaymentFacade paymentFacade;

    @DubboReference(check = false, timeout = 20000, retries = 0)
    private MarketingFacade marketingFacade;

    private final StepUpTokenStore stepUpTokenStore;

    public MarketingPayController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- C-4 最优组合（只读计算，不核销不动账） ----------------

    /** 最优组合推荐：实付最小 + 选中理由 + 落选原因 + 确定性指纹。 */
    @PostMapping("/best")
    public ResponseEntity<Object> best(@RequestBody BestPromotionRequest request) {
        if (!hasAnyPermission("marketing:read", "marketing:write")) {
            return forbidden();
        }
        return GatewaySupport.of(marketingFacade.calcBestPromotion(request));
    }

    // ---------------- P-7 营销抵扣编排 ----------------

    /**
     * 带营销抵扣的支付受理（券→红包→积分→活动，每步幂等 + 失败逆序退回）。
     * 与既有 {@code POST /api/cashier/pay} 同一 {@code PaymentFacade.pay} 入口，
     * 只是 body 多带 additive 的 {@code marketing} 字段；不带该字段时行为与既有路径逐字节一致。
     */
    @PostMapping("/pay")
    public ResponseEntity<Object> pay(@RequestBody PayRequest request,
                                      @RequestHeader(value = STEP_UP_HEADER, required = false) String stepUpToken) {
        ResponseEntity<Object> deny = guard(stepUpToken, "payment:pay", "marketing:write");
        if (deny != null) {
            return deny;
        }
        if (request.getMethod() != null) {
            request.setMethod(request.getMethod().trim().toUpperCase());
            if ("BANKCARD".equals(request.getMethod())) {
                request.setMethod("CARD");
            }
        }
        return GatewaySupport.of(paymentFacade.pay(request));
    }

    /** 抵扣明细 + 分摊 + 守恒三件套（只读，可追溯）。 */
    @GetMapping("/deduction/{paymentId}")
    public ResponseEntity<Object> deduction(@PathVariable String paymentId) {
        if (!hasAnyPermission("marketing:read", "payment:read")) {
            return forbidden();
        }
        return GatewaySupport.of(paymentFacade.getMarketingDeduction(paymentId));
    }

    /** 营销抵扣回退（幂等）：退回已核销权益 + 资金反向 + 目标维度重分摊。 */
    @PostMapping("/rollback/{paymentId}")
    public ResponseEntity<Object> rollback(@PathVariable String paymentId,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           @RequestHeader(value = STEP_UP_HEADER, required = false) String stepUpToken) {
        ResponseEntity<Object> deny = guard(stepUpToken, "payment:refund", "marketing:write");
        return deny != null ? deny
                : GatewaySupport.of(paymentFacade.rollbackMarketing(paymentId, str(body, "requestId")));
    }

    /** 退回后目标维度重分摊（幂等，Σ目标=Σ来源 diff=0）。 */
    @PostMapping("/realloc/{paymentId}")
    public ResponseEntity<Object> realloc(@PathVariable String paymentId,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @RequestHeader(value = STEP_UP_HEADER, required = false) String stepUpToken) {
        ResponseEntity<Object> deny = guard(stepUpToken, "marketing:write", "payment:refund");
        return deny != null ? deny
                : GatewaySupport.of(paymentFacade.reallocateMarketing(paymentId, str(body, "requestId")));
    }

    /** 营销域分摊视图（来源 + 目标双维度）。 */
    @GetMapping("/alloc/{paymentId}")
    public ResponseEntity<Object> alloc(@PathVariable String paymentId) {
        if (!hasAnyPermission("marketing:read")) {
            return forbidden();
        }
        return GatewaySupport.of(marketingFacade.getAllocation(paymentId));
    }

    /** 营销域守恒校验（orderAmount = fundAmount + marketingAmount，三项 diff 全 0）。 */
    @GetMapping("/conservation/{paymentId}")
    public ResponseEntity<Object> conservation(@PathVariable String paymentId,
                                               @RequestParam(required = false) Long orderAmount,
                                               @RequestParam(required = false) Long fundAmount) {
        if (!hasAnyPermission("marketing:read")) {
            return forbidden();
        }
        return GatewaySupport.of(marketingFacade.checkConservation(paymentId, orderAmount, fundAmount));
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
            log.warn("营销编排敏感操作拒绝：step-up token 归属与会话不符 session={} owner={}", user, owner);
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