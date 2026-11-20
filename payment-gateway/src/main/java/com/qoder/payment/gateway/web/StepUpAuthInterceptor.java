package com.qoder.payment.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.review.ReviewDTO;
import com.qoder.payment.facade.review.ReviewFacade;
import com.qoder.payment.gateway.config.StepUpConfig;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S-7 资金防线网关闸门（只作用于控制台 /api/**，/openapi/** 逐字节不变，§0.7 兼容红线）。
 *
 * <p>两道闸：
 * ① 二次认证：敏感端点需 X-Step-Up-Token（POST /api/security/step-up 口令再验颁发，短时）。
 *    恒需 token：复核 approve/reject、备付金模拟调整、密钥轮换、大额退款/冲正。
 *    缺失/失效 → 403。B 未落地（无鉴权）与已落地（Bearer）双态均可工作
 *    （Security 链在前先 401，本拦截器在其后叠加 403）。
 * ② 大额双人复核：退款/冲正金额 ≥ 阈值（payment.review.threshold，payment 域权威判定）
 *    → 查 (bizType,bizNo) 复核单：无单自动建 PENDING；PENDING/REJECTED → A0428 REVIEW_PENDING
 *    拒绝（绝不动账）；APPROVED → 放行原 TCC 动账流程。复核幂等 (REVIEW,bizType:bizNo)。
 *
 * <p>小额退款/冲正不拦；会员/账户状态变更暂不纳入 step-up（既有回归脚本含状态变更，
 * B 泳道登录改造只加 Bearer，纳入即回归；待脚本协同后由主会话收紧）。
 */
@Slf4j
@Component
public class StepUpAuthInterceptor implements HandlerInterceptor {

    /** 大额复核拒绝错误码（REVIEW_PENDING）。 */
    public static final String REVIEW_PENDING_CODE = "A0428";
    private static final String STEP_UP_CODE = "A0403";
    private static final String STEP_UP_HEADER = "X-Step-Up-Token";
    /** requiresReview 判定不可用时的兜底阈值（与 payment 域默认一致，fail-closed）。 */
    private static final long FALLBACK_THRESHOLD = 1_000_000L;

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StepUpTokenStore tokenStore;

    @DubboReference
    private ReviewFacade reviewFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    public StepUpAuthInterceptor(StepUpTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        JsonNode body = readBody(request);

        // ---- 敏感端点识别 ----
        // 恒需 token：复核 approve/reject、备付金模拟调整、密钥轮换、大额退款/冲正。
        // 会员/账户状态变更暂不纳入（既有回归脚本含状态变更且 B 泳道登录改造只加 Bearer，
        // 纳入将造成回归；待脚本协同改造后由主会话收紧，见报告偏差说明）。
        boolean needStepUp = MATCHER.match("/api/reviews/*/approve", path)
                || MATCHER.match("/api/reviews/*/reject", path)
                || MATCHER.match("/api/accounting/reserve/adjust", path)
                || MATCHER.match("/api/members/*/secrets/rotate", path)
                || MATCHER.match("/api/members/*/rotate", path)
                // P1-A 渠道运营写操作：限额/维护窗影响路由与受理，账单比对会建差错单，均属敏感配置变更
                || MATCHER.match("/api/channel/*/limit", path)
                || MATCHER.match("/api/channel/*/maintenance", path)
                || MATCHER.match("/api/channel/bills/*/reconcile", path)
                // P1-D 结算周期配置变更与分账动账/回退（真实动账，UI 已主动加闸，后端补齐一致性）
                || MATCHER.match("/api/settle/cycles", path)
                || MATCHER.match("/api/settle/shares", path)
                || MATCHER.match("/api/settle/shares/back", path)
                || MATCHER.match("/api/disputes/*/approve", path)
                // 批量自动修复：仅显式 execute=true（真实动账）需二次认证；
                // 影子巡检 execute=false 只给建议不动账，不应拦（否则只读巡检也被挡）。
                || (MATCHER.match("/api/disputes/autofix/run", path)
                    && body != null && body.path("execute").asBoolean(false))
                // N-4 additive：通知重发属敏感操作（可触达商户系统），恒需二次认证
                || MATCHER.match("/api/notify/*/resend", path)
                || MATCHER.match("/api/notify/resend/batch", path);

        String refundPaymentId = matchGroup("/api/payments/*/refund", path);
        String reverseBizNo = matchGroup("/api/accounting/transfers/*/reverse", path);
        long refundAmount = refundPaymentId != null ? longField(body, "amount") : -1;
        long reverseAmount = reverseBizNo != null ? reverseAmount(reverseBizNo) : -1;
        boolean largeRefund = refundPaymentId != null && requiresReview(refundAmount);
        boolean largeReverse = reverseBizNo != null && requiresReview(reverseAmount);

        needStepUp = needStepUp || largeRefund || largeReverse;
        String operator = null;
        if (needStepUp) {
            operator = tokenStore.validate(request.getHeader(STEP_UP_HEADER));
            if (operator == null) {
                log.warn("二次认证拒绝 path={}（X-Step-Up-Token 缺失或失效）", path);
                writeJson(response, 403, Map.of(
                        "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                        "code", STEP_UP_CODE));
                return false;
            }
        }

        // ---- 大额双人复核闸门（未复核绝不动账） ----
        if (largeRefund) {
            String requestId = textField(body, "requestId");
            if (requestId != null && !requestId.isBlank()) {
                return reviewGate(response, "REFUND", requestId, refundAmount, operator, body);
            }
        }
        if (largeReverse) {
            return reviewGate(response, "REVERSE", reverseBizNo, reverseAmount, operator, body);
        }
        return true;
    }

    /** 复核闸门：APPROVED 放行；无单自动建 PENDING；PENDING/REJECTED → A0428。 */
    private boolean reviewGate(HttpServletResponse response, String bizType, String bizNo,
                               long amount, String operator, JsonNode body) throws Exception {
        ReviewDTO review = orNull(reviewFacade.findByBiz(bizType, bizNo));
        if (review == null) {
            // 提交人恒取二次认证操作员（已绑会话），不接受 body 冒充他人，否则双人原则可绕过
            String submitter = operator != null && !operator.isBlank() ? operator : textField(body, "submitter");
            if (submitter == null || submitter.isBlank()) {
                submitter = "console";
            }
            review = orNull(reviewFacade.submitReview(bizType, bizNo, amount, submitter));
        }
        if (review == null) {
            writeJson(response, 503, Map.of("error", "复核服务不可用，大额操作拒绝", "code", "C0001"));
            return false;
        }
        if (AmountReviewStatus.APPROVED.equals(review.getStatus())) {
            log.info("大额复核放行 bizType={} bizNo={} reviewId={} approver={}",
                    bizType, bizNo, review.getId(), review.getApprover());
            return true;
        }
        log.warn("大额复核拦截 bizType={} bizNo={} amount={} reviewId={} status={}",
                bizType, bizNo, amount, review.getId(), review.getStatus());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "REVIEW_PENDING: 大额操作需双人复核（" + review.getStatus() + "），reviewId=" + review.getId());
        payload.put("code", REVIEW_PENDING_CODE);
        payload.put("reviewId", review.getId());
        payload.put("reviewStatus", review.getStatus());
        writeJson(response, 400, payload);
        return false;
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
        } catch (Exception e) {
            log.warn("复核阈值判定失败，兜底阈值 {} fail-closed: {}", FALLBACK_THRESHOLD, e.getMessage());
        }
        return amount >= FALLBACK_THRESHOLD;
    }

    /** 冲正金额=原凭证借方合计（全额反向）；原凭证不存在则不拦（由控制器返回 404）。 */
    private long reverseAmount(String bizNo) {
        try {
            Result<TransferDTO> result = accountingFacade.transfer(bizNo);
            if (!result.isSuccess() || result.getData() == null || result.getData().getLines() == null) {
                return -1;
            }
            long sum = 0;
            for (TransferLineDTO line : result.getData().getLines()) {
                if ("D".equals(line.getDir())) {
                    sum += line.getAmount();
                }
            }
            return sum;
        } catch (Exception e) {
            log.warn("冲正原凭证查询失败 bizNo={}: {}", bizNo, e.getMessage());
            return -1;
        }
    }

    private static String matchGroup(String pattern, String path) {
        if (!MATCHER.match(pattern, path)) {
            return null;
        }
        // 取倒数第二段（/api/payments/{id}/refund、/api/accounting/transfers/{bizNo}/reverse）
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }

    private static JsonNode readBody(HttpServletRequest request) {
        StepUpConfig.CachedBodyRequest cached =
                WebUtils.getNativeRequest(request, StepUpConfig.CachedBodyRequest.class);
        if (cached == null || cached.cachedBody().length == 0) {
            return null;
        }
        try {
            return JSON.readTree(cached.cachedBody());
        } catch (Exception e) {
            return null;
        }
    }

    private static long longField(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return -1;
        }
        try {
            return body.get(field).asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String textField(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        return body.get(field).asText(null);
    }

    private static <T> T orNull(Result<T> result) {
        return result != null && result.isSuccess() ? result.getData() : null;
    }

    private static void writeJson(HttpServletResponse response, int status, Map<String, ?> payload)
            throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // G-3 additive：统一错误体追加 traceId（error/code 及既有附加字段口径不变）
        Map<String, Object> body = new LinkedHashMap<>(payload);
        String traceId = GatewaySupport.traceId();
        if (traceId != null && !traceId.isBlank()) {
            body.putIfAbsent("traceId", traceId);
        }
        response.getOutputStream().write(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /** 复核终态常量（与 payment 域 AmountReviewService 对齐，避免跨模块依赖实现类）。 */
    private static final class AmountReviewStatus {
        private static final String APPROVED = "APPROVED";
    }
}