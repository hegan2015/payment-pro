package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.review.ReviewDTO;
import com.qoder.payment.facade.review.ReviewFacade;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import com.qoder.payment.gateway.security.AuditSupport;
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

import java.util.List;
import java.util.Map;

/**
 * S-7 大额双人复核 REST（裸数据返回，与网关既有风格一致）。
 * approve/reject 为敏感端点：需 X-Step-Up-Token（StepUpAuthInterceptor，缺失 403）。
 * approver 缺省取 step-up token 操作员；与 submitter 同人拒绝（双人原则，payment 域校验）。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    @DubboReference
    private ReviewFacade reviewFacade;

    private final StepUpTokenStore tokenStore;

    public ReviewController(StepUpTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String status) {
        Result<List<ReviewDTO>> result = reviewFacade.list(status);
        return GatewaySupport.of(result);
    }

    /** 提交复核单（便于运营/测试显式建单；(REVIEW,bizType:bizNo) 幂等不重复建单）。提交人恒取会话用户。 */
    @PostMapping
    public ResponseEntity<Object> submit(@RequestBody Map<String, Object> body) {
        String bizType = str(body.get("bizType"));
        String bizNo = str(body.get("bizNo"));
        long amount = body.get("amount") instanceof Number n ? n.longValue() : Long.parseLong(str(body.get("amount")));
        String sessionUser = AuditSupport.currentUsername();
        String claimed = str(body.get("submitter"));
        if (claimed != null && !claimed.isBlank() && !claimed.equals(sessionUser)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "提交人必须是当前登录账号（双人复核不可冒充他人）", "code", "A0403"));
        }
        return GatewaySupport.of(reviewFacade.submitReview(bizType, bizNo, amount, sessionUser));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Object> approve(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        // 审批人恒取二次认证操作员（已绑会话），不接受 body 指定他人，否则同人可自审自批
        String approver = tokenStore.validate(request.getHeader("X-Step-Up-Token"));
        if (approver == null || approver.isBlank()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "审批需二次认证：X-Step-Up-Token 缺失或失效", "code", "A0403"));
        }
        String claimed = body == null ? null : str(body.get("approver"));
        if (claimed != null && !claimed.isBlank() && !claimed.equals(approver)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "审批人必须是当前登录账号（双人复核不可冒充他人）", "code", "A0403"));
        }
        return GatewaySupport.of(reviewFacade.approve(id, approver));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Object> reject(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return GatewaySupport.of(reviewFacade.reject(id, body == null ? null : str(body.get("reason"))));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}