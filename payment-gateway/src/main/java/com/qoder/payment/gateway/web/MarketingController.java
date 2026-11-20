package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.marketing.ActivityApplyRequest;
import com.qoder.payment.facade.marketing.ActivityDTO;
import com.qoder.payment.facade.marketing.ClaimRequest;
import com.qoder.payment.facade.marketing.CouponIssueRequest;
import com.qoder.payment.facade.marketing.CouponUseRequest;
import com.qoder.payment.facade.marketing.MarketingAllocRequest;
import com.qoder.payment.facade.marketing.MarketingFacade;
import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import com.qoder.payment.facade.marketing.PointsEarnRequest;
import com.qoder.payment.facade.marketing.PointsFreezeRequest;
import com.qoder.payment.facade.marketing.PointsUseRequest;
import com.qoder.payment.facade.marketing.RedPacketIssueRequest;
import com.qoder.payment.facade.marketing.RedPacketUseRequest;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P2 第三期 · X-3a/X-3b 营销域 REST（<b>独立新增控制器</b>，不改 ApiController/OpenApiController/SecurityConfig）。
 *
 * <p>薄适配层，业务经 Dubbo 路由到 marketing 域（20893）；返回裸数据（与既有控制台风格一致）。
 *
 * <p><b>安全</b>：SecurityConfig 现有兜底规则 {@code /api/** authenticated} 天然覆盖本控制器
 * （未登录 401）；专属权限点 {@code marketing:read / marketing:write / marketing:use} 的映射
 * 需主会话补 SecurityConfig（本泳道不改该共享文件）。<b>资金/配置类敏感写操作在本控制器内强制
 * X-Step-Up-Token</b>（复用既有 {@link StepUpTokenStore}，再验会话用户自己的口令），
 * 且 {@code operator} 恒取二次认证返回的会话用户，忽略入参伪造。
 *
 * <p><b>ride 红线</b>：只挂 {@code /api/**}，{@code /openapi/**} 一字未改——8 个冻结方法、
 * BALANCE 同步语义、feeBps→SYS_FEE 手续费口径、notify http 推送均不受影响。
 * 营销域未启动时本控制器返回 Dubbo 无提供者错误（B0002/C0001），不影响其它 13 进程与 13/13 健康断言。
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private MarketingFacade marketingFacade;

    public MarketingController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- X-3a 模板 ----------------

    @PostMapping("/templates")
    public ResponseEntity<Object> saveTemplate(@RequestBody MarketingTemplateDTO template,
                                               HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        template.setOperator(operator);
        return GatewaySupport.of(marketingFacade.saveTemplate(template));
    }

    @GetMapping("/templates")
    public ResponseEntity<Object> templates(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String status) {
        return GatewaySupport.of(marketingFacade.listTemplates(kind, status));
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<Object> template(@PathVariable String id) {
        return GatewaySupport.of(marketingFacade.getTemplate(id));
    }

    // ---------------- X-3a 优惠券 ----------------

    @PostMapping("/coupons/issue")
    public ResponseEntity<Object> issueCoupon(@RequestBody CouponIssueRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.issueCoupon(body));
    }

    @PostMapping("/coupons/claim")
    public ResponseEntity<Object> claimCoupon(@RequestBody ClaimRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.claimCoupon(body));
    }

    @GetMapping("/coupons")
    public ResponseEntity<Object> coupons(@RequestParam String memberId,
                                          @RequestParam(required = false) String status) {
        return GatewaySupport.of(marketingFacade.listCoupons(memberId, status));
    }

    @GetMapping("/coupons/{id}")
    public ResponseEntity<Object> coupon(@PathVariable String id) {
        return GatewaySupport.of(marketingFacade.getCoupon(id));
    }

    @PostMapping("/coupons/use")
    public ResponseEntity<Object> useCoupon(@RequestBody CouponUseRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.useCoupon(body));
    }

    @PostMapping("/coupons/refund/{useId}")
    public ResponseEntity<Object> refundCoupon(@PathVariable String useId, HttpServletRequest request) {
        return operator(request) == null ? denied() : GatewaySupport.of(marketingFacade.refundCoupon(useId));
    }

    // ---------------- X-3a 红包 ----------------

    @PostMapping("/red-packets/issue")
    public ResponseEntity<Object> issueRedPacket(@RequestBody RedPacketIssueRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.issueRedPacket(body));
    }

    @PostMapping("/red-packets/claim")
    public ResponseEntity<Object> claimRedPacket(@RequestBody ClaimRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.claimRedPacket(body));
    }

    @GetMapping("/red-packets")
    public ResponseEntity<Object> redPackets(@RequestParam String memberId,
                                             @RequestParam(required = false) String status) {
        return GatewaySupport.of(marketingFacade.listRedPackets(memberId, status));
    }

    @GetMapping("/red-packets/{id}")
    public ResponseEntity<Object> redPacket(@PathVariable String id) {
        return GatewaySupport.of(marketingFacade.getRedPacket(id));
    }

    @PostMapping("/red-packets/use")
    public ResponseEntity<Object> useRedPacket(@RequestBody RedPacketUseRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.useRedPacket(body));
    }

    @PostMapping("/red-packets/refund/{useId}")
    public ResponseEntity<Object> refundRedPacket(@PathVariable String useId, HttpServletRequest request) {
        return operator(request) == null ? denied() : GatewaySupport.of(marketingFacade.refundRedPacket(useId));
    }

    // ---------------- X-3a 可用优惠 / 过期 ----------------

    @GetMapping("/available")
    public ResponseEntity<Object> available(@RequestParam String memberId,
                                            @RequestParam(required = false) Long orderAmount) {
        return GatewaySupport.of(marketingFacade.listAvailable(memberId, orderAmount));
    }

    @PostMapping("/expire-vouchers")
    public ResponseEntity<Object> expireVouchers(HttpServletRequest request) {
        return operator(request) == null ? denied() : GatewaySupport.of(marketingFacade.expireVouchers());
    }

    // ---------------- X-3b 积分 ----------------

    @GetMapping("/points/{memberId}")
    public ResponseEntity<Object> points(@PathVariable String memberId) {
        return GatewaySupport.of(marketingFacade.pointsAccount(memberId));
    }

    @GetMapping("/points/{memberId}/logs")
    public ResponseEntity<Object> pointsLogs(@PathVariable String memberId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return GatewaySupport.of(marketingFacade.pointsLogs(memberId, limit));
    }

    @PostMapping("/points/earn")
    public ResponseEntity<Object> earnPoints(@RequestBody PointsEarnRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.earnPoints(body));
    }

    @PostMapping("/points/use")
    public ResponseEntity<Object> usePoints(@RequestBody PointsUseRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.usePoints(body));
    }

    @PostMapping("/points/freeze")
    public ResponseEntity<Object> freezePoints(@RequestBody PointsFreezeRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.freezePoints(body));
    }

    @PostMapping("/points/unfreeze")
    public ResponseEntity<Object> unfreezePoints(@RequestBody PointsFreezeRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.unfreezePoints(body));
    }

    @PostMapping("/points/refund")
    public ResponseEntity<Object> refundPoints(@RequestParam String origBizNo,
                                               @RequestParam(required = false) String requestId,
                                               HttpServletRequest request) {
        return operator(request) == null ? denied()
                : GatewaySupport.of(marketingFacade.refundPoints(origBizNo, requestId));
    }

    @PostMapping("/expire-points")
    public ResponseEntity<Object> expirePoints(HttpServletRequest request) {
        return operator(request) == null ? denied() : GatewaySupport.of(marketingFacade.expirePoints());
    }

    // ---------------- X-3b 活动 ----------------

    @PostMapping("/activities")
    public ResponseEntity<Object> saveActivity(@RequestBody ActivityDTO body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.saveActivity(body));
    }

    @GetMapping("/activities")
    public ResponseEntity<Object> activities(@RequestParam(required = false) Long orderAmount) {
        return GatewaySupport.of(marketingFacade.listActivities(orderAmount));
    }

    @GetMapping("/activities/{id}")
    public ResponseEntity<Object> activity(@PathVariable String id) {
        return GatewaySupport.of(marketingFacade.getActivity(id));
    }

    @PostMapping("/activities/apply")
    public ResponseEntity<Object> applyActivity(@RequestBody ActivityApplyRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.applyActivity(body));
    }

    @PostMapping("/activities/refund/{useId}")
    public ResponseEntity<Object> refundActivity(@PathVariable String useId, HttpServletRequest request) {
        return operator(request) == null ? denied() : GatewaySupport.of(marketingFacade.refundActivity(useId));
    }

    // ---------------- X-3b 分摊联动（P-7 预留接口，不含编排） ----------------

    @PostMapping("/alloc")
    public ResponseEntity<Object> allocate(@RequestBody MarketingAllocRequest body, HttpServletRequest request) {
        String operator = operator(request);
        if (operator == null) {
            return denied();
        }
        body.setOperator(operator);
        return GatewaySupport.of(marketingFacade.allocate(body));
    }

    @GetMapping("/alloc/{paymentId}")
    public ResponseEntity<Object> allocation(@PathVariable String paymentId) {
        return GatewaySupport.of(marketingFacade.getAllocation(paymentId));
    }

    @GetMapping("/conservation/{paymentId}")
    public ResponseEntity<Object> conservation(@PathVariable String paymentId,
                                               @RequestParam(required = false) Long orderAmount,
                                               @RequestParam(required = false) Long fundAmount) {
        return GatewaySupport.of(marketingFacade.checkConservation(paymentId, orderAmount, fundAmount));
    }

    // ---------------- 资金护航 / 审计 ----------------

    @GetMapping("/fund-check")
    public ResponseEntity<Object> fundCheck() {
        return GatewaySupport.of(marketingFacade.fundCheck());
    }

    @GetMapping("/audit")
    public ResponseEntity<Object> audit(@RequestParam(required = false) String entityId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return GatewaySupport.of(marketingFacade.listAudit(entityId, limit));
    }

    /** 二次认证：返回会话用户名（operator 恒取会话用户），缺失/失效返回 null。 */
    private String operator(HttpServletRequest request) {
        return stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
    }

    private static ResponseEntity<Object> denied() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                "code", "A0403"));
    }
}