package com.qoder.payment.marketing.web;

import com.qoder.payment.common.result.Result;
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
import com.qoder.payment.marketing.config.MarketingSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * X-3 <b>local 形态自检 API</b>（{@code /local/marketing/**}，仅 {@code payment.marketing.local-api=true} 装配）。
 *
 * <p>用途：local H2 零依赖形态下，营销域可<b>单进程</b>启动并被 verify-marketing.py 直接验证
 * （无需网关/其它域）。docker profile 显式 {@code local-api=false}，生产面只经网关
 * {@code /api/marketing/**}（Bearer + RBAC + 审计），本控制器不装配、不暴露任何端点。
 *
 * <p>薄适配层：与网关控制器调用<b>同一</b> {@link MarketingFacade} 实现，业务口径完全一致；
 * 返回裸数据（与既有控制台风格一致），失败返回 {error, code}。
 */
@RestController
@RequestMapping("/local/marketing")
@ConditionalOnProperty(name = "payment.marketing.local-api", havingValue = "true")
@RequiredArgsConstructor
public class MarketingLocalController {

    private final MarketingFacade marketingFacade;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    /** 形态自证：profile / 数据库产品 / 营销表清单（local H2 与 docker MySQL 同构证据）。 */
    @GetMapping("/profile")
    public ResponseEntity<Object> profile() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", environment.getProperty("spring.application.name"));
        body.put("profiles", environment.getActiveProfiles());
        body.put("localApi", environment.getProperty("payment.marketing.local-api"));
        body.put("shardingEnabled", environment.getProperty("payment.sharding.enabled"));
        body.put("tables", MarketingSchema.TABLES);
        DataSource ds = jdbc.getDataSource();
        try (Connection con = ds.getConnection()) {
            body.put("database", con.getMetaData().getDatabaseProductName());
            body.put("url", con.getMetaData().getURL());
        } catch (Exception e) {
            body.put("database", "UNKNOWN");
        }
        return ResponseEntity.ok(body);
    }

    // ---------------- 模板 ----------------

    @PostMapping("/templates")
    public ResponseEntity<Object> saveTemplate(@RequestBody MarketingTemplateDTO template) {
        return of(marketingFacade.saveTemplate(template));
    }

    @GetMapping("/templates")
    public ResponseEntity<Object> templates(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String status) {
        return of(marketingFacade.listTemplates(kind, status));
    }

    // ---------------- 券 ----------------

    @PostMapping("/coupons/issue")
    public ResponseEntity<Object> issueCoupon(@RequestBody CouponIssueRequest request) {
        return of(marketingFacade.issueCoupon(request));
    }

    @PostMapping("/coupons/claim")
    public ResponseEntity<Object> claimCoupon(@RequestBody ClaimRequest request) {
        return of(marketingFacade.claimCoupon(request));
    }

    @GetMapping("/coupons")
    public ResponseEntity<Object> coupons(@RequestParam String memberId,
                                          @RequestParam(required = false) String status) {
        return of(marketingFacade.listCoupons(memberId, status));
    }

    @PostMapping("/coupons/use")
    public ResponseEntity<Object> useCoupon(@RequestBody CouponUseRequest request) {
        return of(marketingFacade.useCoupon(request));
    }

    @PostMapping("/coupons/refund/{useId}")
    public ResponseEntity<Object> refundCoupon(@PathVariable String useId) {
        return of(marketingFacade.refundCoupon(useId));
    }

    // ---------------- 红包 ----------------

    @PostMapping("/red-packets/issue")
    public ResponseEntity<Object> issueRedPacket(@RequestBody RedPacketIssueRequest request) {
        return of(marketingFacade.issueRedPacket(request));
    }

    @PostMapping("/red-packets/claim")
    public ResponseEntity<Object> claimRedPacket(@RequestBody ClaimRequest request) {
        return of(marketingFacade.claimRedPacket(request));
    }

    @GetMapping("/red-packets")
    public ResponseEntity<Object> redPackets(@RequestParam String memberId,
                                             @RequestParam(required = false) String status) {
        return of(marketingFacade.listRedPackets(memberId, status));
    }

    @PostMapping("/red-packets/use")
    public ResponseEntity<Object> useRedPacket(@RequestBody RedPacketUseRequest request) {
        return of(marketingFacade.useRedPacket(request));
    }

    @PostMapping("/red-packets/refund/{useId}")
    public ResponseEntity<Object> refundRedPacket(@PathVariable String useId) {
        return of(marketingFacade.refundRedPacket(useId));
    }

    // ---------------- 可用 / 过期 ----------------

    @GetMapping("/available")
    public ResponseEntity<Object> available(@RequestParam String memberId,
                                            @RequestParam(required = false) Long orderAmount) {
        return of(marketingFacade.listAvailable(memberId, orderAmount));
    }

    @PostMapping("/expire-vouchers")
    public ResponseEntity<Object> expireVouchers() {
        return of(marketingFacade.expireVouchers());
    }

    // ---------------- 积分 ----------------

    @GetMapping("/points/{memberId}")
    public ResponseEntity<Object> points(@PathVariable String memberId) {
        return of(marketingFacade.pointsAccount(memberId));
    }

    @PostMapping("/points/earn")
    public ResponseEntity<Object> earn(@RequestBody PointsEarnRequest request) {
        return of(marketingFacade.earnPoints(request));
    }

    @PostMapping("/points/use")
    public ResponseEntity<Object> usePoints(@RequestBody PointsUseRequest request) {
        return of(marketingFacade.usePoints(request));
    }

    @PostMapping("/points/freeze")
    public ResponseEntity<Object> freeze(@RequestBody PointsFreezeRequest request) {
        return of(marketingFacade.freezePoints(request));
    }

    @PostMapping("/points/unfreeze")
    public ResponseEntity<Object> unfreeze(@RequestBody PointsFreezeRequest request) {
        return of(marketingFacade.unfreezePoints(request));
    }

    @PostMapping("/points/refund")
    public ResponseEntity<Object> refundPoints(@RequestParam String origBizNo,
                                               @RequestParam(required = false) String requestId) {
        return of(marketingFacade.refundPoints(origBizNo, requestId));
    }

    @GetMapping("/points/{memberId}/logs")
    public ResponseEntity<Object> pointsLogs(@PathVariable String memberId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return of(marketingFacade.pointsLogs(memberId, limit));
    }

    @PostMapping("/expire-points")
    public ResponseEntity<Object> expirePoints() {
        return of(marketingFacade.expirePoints());
    }

    // ---------------- 活动 ----------------

    @PostMapping("/activities")
    public ResponseEntity<Object> saveActivity(@RequestBody ActivityDTO activity) {
        return of(marketingFacade.saveActivity(activity));
    }

    @GetMapping("/activities")
    public ResponseEntity<Object> activities(@RequestParam(required = false) Long orderAmount) {
        return of(marketingFacade.listActivities(orderAmount));
    }

    @PostMapping("/activities/apply")
    public ResponseEntity<Object> applyActivity(@RequestBody ActivityApplyRequest request) {
        return of(marketingFacade.applyActivity(request));
    }

    @PostMapping("/activities/refund/{useId}")
    public ResponseEntity<Object> refundActivity(@PathVariable String useId) {
        return of(marketingFacade.refundActivity(useId));
    }

    // ---------------- 分摊 / 守恒 / 资金核对 / 审计 ----------------

    @PostMapping("/alloc")
    public ResponseEntity<Object> allocate(@RequestBody MarketingAllocRequest request) {
        return of(marketingFacade.allocate(request));
    }

    @GetMapping("/alloc/{paymentId}")
    public ResponseEntity<Object> allocation(@PathVariable String paymentId) {
        return of(marketingFacade.getAllocation(paymentId));
    }

    @GetMapping("/conservation/{paymentId}")
    public ResponseEntity<Object> conservation(@PathVariable String paymentId,
                                               @RequestParam(required = false) Long orderAmount,
                                               @RequestParam(required = false) Long fundAmount) {
        return of(marketingFacade.checkConservation(paymentId, orderAmount, fundAmount));
    }

    @GetMapping("/fund-check")
    public ResponseEntity<Object> fundCheck() {
        return of(marketingFacade.fundCheck());
    }

    @GetMapping("/audit")
    public ResponseEntity<Object> audit(@RequestParam(required = false) String entityId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return of(marketingFacade.listAudit(entityId, limit));
    }

    // ---------------- P2 · E 泳道 additive（C-4 最优组合 / P-7 退回后重分摊，local 零依赖可验） ----------------

    @PostMapping("/promotion/best")
    public ResponseEntity<Object> bestPromotion(
            @RequestBody com.qoder.payment.facade.marketing.BestPromotionRequest request) {
        return of(marketingFacade.calcBestPromotion(request));
    }

    @PostMapping("/alloc/realloc/{paymentId}")
    public ResponseEntity<Object> reallocate(@PathVariable String paymentId,
                                             @RequestParam(required = false) String requestId) {
        return of(marketingFacade.reallocate(paymentId, requestId));
    }

    private static ResponseEntity<Object> of(Result<?> result) {
        if (result.isSuccess()) {
            return ResponseEntity.ok(result.getData());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", result.getMessage());
        body.put("code", result.getCode());
        return ResponseEntity.status(statusOf(result.getCode())).body(body);
    }

    private static int statusOf(String code) {
        if (code == null) {
            return 500;
        }
        return switch (code) {
            case "A0400" -> 400;
            case "A0404" -> 404;
            case "A0409" -> 409;
            case "B0001" -> 400;
            case "B0003" -> 503;
            case "C0001" -> 502;
            default -> 500;
        };
    }
}