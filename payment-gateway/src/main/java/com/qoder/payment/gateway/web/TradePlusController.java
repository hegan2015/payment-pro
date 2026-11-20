package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.payment.AdjustPriceRequest;
import com.qoder.payment.facade.payment.MergePayRequest;
import com.qoder.payment.facade.payment.PayLeg;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.StagePayRequest;
import com.qoder.payment.facade.review.ReviewDTO;
import com.qoder.payment.facade.review.ReviewFacade;
import com.qoder.payment.facade.trade.MergeCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 · H 泳道 T-5/T-6 REST（合并支付/分阶段 + 撤销/改价，<b>独立新增控制器</b>，
 * 不改 ApiController/OpenApiController/TradeOpsController/PaymentOpsController 任何一行）。
 *
 * <p><b>安全</b>：落既有 SecurityConfig {@code /api/** authenticated} 兜底（ADMIN 通配权限）。
 * 资金动账写操作（合并支付/阶段支付/改价/差额补退/撤销）在本控制器内<b>强制 X-Step-Up-Token</b>；
 * 大额资金反向（差额补退下调差额、撤销全额）另经<b>双人复核闸门</b>（复用既有 ReviewFacade，
 * 未复核 A0428 绝不动账，approve 走既有 /api/reviews/{id}/approve 第二人审批）。
 *
 * <p><b>ride 红线</b>：{@code /openapi/**} 一字未改；本控制器只服务控制台新能力，
 * trade.create/pay/query 与其余冻结方法语义/返回逐字节不变。
 */
@RestController
@RequestMapping("/api/trade-plus")
public class TradePlusController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";
    private static final long FALLBACK_THRESHOLD = 1_000_000L;

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private TradeFacade tradeFacade;

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private ReviewFacade reviewFacade;

    public TradePlusController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- T-5 合并支付 ----------------

    /** 创建合并单（不动账）。body {orderIds[], requestId}。 */
    @PostMapping("/orders/merge")
    public ResponseEntity<Object> createMerge(@RequestBody Map<String, Object> body) {
        MergeCreateRequest request = new MergeCreateRequest();
        request.setOrderIds(strList(body.get("orderIds")));
        request.setRequestId(strOf(body, "requestId"));
        return GatewaySupport.of(tradeFacade.createMergeOrder(request));
    }

    /** 合并单查询（含子订单实时状态/fee 分摊快照）。 */
    @GetMapping("/merges/{id}")
    public ResponseEntity<Object> merge(@PathVariable String id) {
        return GatewaySupport.of(tradeFacade.getMergeOrder(id));
    }

    /** 合并单拆分（明确拆分语义：子订单解绑可个别支付；不动账）。 */
    @PostMapping("/merges/{id}/dissolve")
    public ResponseEntity<Object> dissolve(@PathVariable String id) {
        return GatewaySupport.of(tradeFacade.dissolveMerge(id));
    }

    /** 合并支付（敏感：需二次认证）。body {accountId, requestId, legs?, callbackUrl?}；legs 非空叠加 COMBO。 */
    @PostMapping("/merges/{id}/pay")
    public ResponseEntity<Object> payMerge(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        MergePayRequest req = new MergePayRequest();
        req.setMergeOrderId(id);
        req.setAccountId(strOf(body, "accountId"));
        req.setRequestId(strOf(body, "requestId"));
        req.setCallbackUrl(strOf(body, "callbackUrl"));
        req.setLegs(legsOf(body.get("legs")));
        return GatewaySupport.of(paymentFacade.payMerge(req));
    }

    // ---------------- T-5 分阶段 ----------------

    /** 创建分阶段计划（不动账）。body {amounts[], requestId}，Σ期额=订单额。 */
    @PostMapping("/orders/{id}/stage-plan")
    public ResponseEntity<Object> stagePlan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        List<Long> amounts = new ArrayList<>();
        for (Object value : listOf(body.get("amounts"))) {
            Long amount = longOf(value);
            if (amount == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "amounts 必须为整数（分）", "code", "A0400"));
            }
            amounts.add(amount);
        }
        return GatewaySupport.of(tradeFacade.createStagePlan(id, amounts, strOf(body, "requestId")));
    }

    /** 分阶段付款（敏感：需二次认证）。body {stageSeq, amount?, accountId, requestId, callbackUrl?}。 */
    @PostMapping("/orders/{id}/stage-pay")
    public ResponseEntity<Object> stagePay(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        StagePayRequest req = new StagePayRequest();
        req.setOrderId(id);
        Long seq = longOf(body, "stageSeq");
        req.setStageSeq(seq == null ? null : seq.intValue());
        req.setAmount(longOf(body, "amount"));
        req.setAccountId(strOf(body, "accountId"));
        req.setRequestId(strOf(body, "requestId"));
        req.setCallbackUrl(strOf(body, "callbackUrl"));
        return GatewaySupport.of(paymentFacade.payStage(req));
    }

    // ---------------- T-6 改价 / 撤销 ----------------

    /** 未支付改价（敏感：需二次认证）。body {newAmount, reason, requestId}。 */
    @PostMapping("/orders/{id}/price")
    public ResponseEntity<Object> changePrice(@PathVariable String id, @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        Long newAmount = longOf(body, "newAmount");
        if (newAmount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "newAmount 必填", "code", "A0400"));
        }
        return GatewaySupport.of(tradeFacade.changePrice(id, newAmount, strOf(body, "reason"), strOf(body, "requestId")));
    }

    /**
     * 已支付改价差额补退（敏感：需二次认证；下调差额大额另需双人复核 A0428）。
     * body {newAmount, reason, requestId, accountId?}。
     */
    @PostMapping("/orders/{id}/adjust-price")
    public ResponseEntity<Object> adjustPrice(@PathVariable String id, @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (operator == null) {
            return stepUpDenied();
        }
        Long newAmount = longOf(body, "newAmount");
        if (newAmount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "newAmount 必填", "code", "A0400"));
        }
        String requestId = strOf(body, "requestId");
        // 大额闸门：下调差额 ≥ 阈值 → 双人复核（未复核绝不动账）；上调/等额/未支付不拦（由域内守卫判定）
        Result<OrderDTO> orderResult = tradeFacade.getOrder(id);
        if (orderResult.isSuccess() && orderResult.getData() != null) {
            OrderDTO order = orderResult.getData();
            long refunded = order.getRefundedAmount() == null ? 0L : order.getRefundedAmount();
            long oldEffective = (order.getAmount() == null ? 0L : order.getAmount()) - refunded;
            if (("PAID".equals(order.getStatus()) || "PARTIAL_REFUNDED".equals(order.getStatus()))
                    && newAmount < oldEffective) {
                ResponseEntity<Object> gated = reviewGate("ADJUST_PRICE", requestId, oldEffective - newAmount, operator);
                if (gated != null) {
                    return gated;
                }
            }
        }
        AdjustPriceRequest req = new AdjustPriceRequest();
        req.setOrderId(id);
        req.setNewAmount(newAmount);
        req.setReason(strOf(body, "reason"));
        req.setRequestId(requestId);
        req.setAccountId(strOf(body, "accountId"));
        return GatewaySupport.of(paymentFacade.adjustPrice(req));
    }

    /**
     * 当日撤销（敏感：需二次认证；大额全额反向另需双人复核 A0428）。
     * 与既有 /api/payments/{id}/cancel 同一域实现（P-5 CAS 守卫），本入口叠加资金防线闸门。
     */
    @PostMapping("/payments/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String id, @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (operator == null) {
            return stepUpDenied();
        }
        Result<PaymentDTO> paymentResult = paymentFacade.getPayment(id);
        if (paymentResult.isSuccess() && paymentResult.getData() != null
                && "SUCCESS".equals(paymentResult.getData().getStatus())) {
            long amount = paymentResult.getData().getAmount() == null ? 0L : paymentResult.getData().getAmount();
            ResponseEntity<Object> gated = reviewGate("PAY_CANCEL", id, amount, operator);
            if (gated != null) {
                return gated;
            }
        }
        return GatewaySupport.of(paymentFacade.cancel(id, strOf(body, "requestId")));
    }

    /** 改价留痕查询（审计，只读）。 */
    @GetMapping("/orders/{id}/price-logs")
    public ResponseEntity<Object> priceLogs(@PathVariable String id) {
        return GatewaySupport.of(tradeFacade.listPriceLogs(id));
    }

    // ---------------- 闸门 ----------------

    private ResponseEntity<Object> requireStepUp(HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        return operator == null ? stepUpDenied() : null;
    }

    private static ResponseEntity<Object> stepUpDenied() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                "code", "A0403"));
    }

    /** 复核闸门（与 StepUpAuthInterceptor 同一口径）：APPROVED 放行；无单自动建 PENDING；PENDING/REJECTED → A0428。 */
    private ResponseEntity<Object> reviewGate(String bizType, String bizNo, long amount, String operator) {
        if (bizNo == null || bizNo.isBlank() || !requiresReview(amount)) {
            return null;
        }
        ReviewDTO review = orNull(reviewFacade.findByBiz(bizType, bizNo));
        if (review == null) {
            review = orNull(reviewFacade.submitReview(bizType, bizNo, amount,
                    operator == null || operator.isBlank() ? "console" : operator));
        }
        if (review == null) {
            return ResponseEntity.status(503).body(Map.of("error", "复核服务不可用，大额操作拒绝", "code", "C0001"));
        }
        if ("APPROVED".equals(review.getStatus())) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "REVIEW_PENDING: 大额操作需双人复核（" + review.getStatus() + "），reviewId=" + review.getId());
        payload.put("code", "A0428");
        payload.put("reviewId", review.getId());
        payload.put("reviewStatus", review.getStatus());
        return ResponseEntity.badRequest().body(payload);
    }

    private boolean requiresReview(long amount) {
        if (amount <= 0) {
            return false;
        }
        try {
            Result<Boolean> result = reviewFacade.requiresReview(amount);
            if (result.isSuccess() && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception ignored) {
            // fail-closed 兜底阈值
        }
        return amount >= FALLBACK_THRESHOLD;
    }

    private static <T> T orNull(Result<T> result) {
        return result != null && result.isSuccess() ? result.getData() : null;
    }

    // ---------------- 参数解析 ----------------

    private static String strOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        return longOf(body == null ? null : body.get(key));
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> strList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : listOf(value)) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<PayLeg> legsOf(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<PayLeg> legs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) item;
            PayLeg leg = new PayLeg();
            leg.setMethod(strOf(row, "method"));
            leg.setAccountId(strOf(row, "accountId"));
            leg.setCardNo(strOf(row, "cardNo"));
            leg.setAmount(longOf(row, "amount"));
            legs.add(leg);
        }
        return legs;
    }
}