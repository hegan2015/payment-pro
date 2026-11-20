package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.sign.AgentPayRequest;
import com.qoder.payment.facade.sign.SignFacade;
import com.qoder.payment.facade.sign.SignRequest;
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

import java.util.Map;

/**
 * CH-6 签约代扣 + X-4 账户授权控制台 REST（<b>新增独立控制器，不改网关既有 java 文件与 static/</b>）。
 *
 * <p>薄适配层：业务经 Dubbo 路由到 channel 域 {@code SignFacade}，返回裸数据（与既有控制器风格一致）。
 *
 * <p><b>鉴权（不改 SecurityConfig）</b>：路径挂 {@code /api/channel/**}，安全链既有规则天然覆盖
 * —— GET → {@code channel:read}，POST → {@code channel:write}（ADMIN 通配 {@code *}）。
 * <b>敏感写操作</b>（签约/解约/代扣/重试/终止/撤销授权/作业驱动）在本控制器内强制
 * {@code X-Step-Up-Token}（复用既有 {@link StepUpTokenStore}，再验会话用户自己的口令），
 * {@code operator} 恒取会话用户名，不接受 body 冒充他人；缺失/失效 → 403 A0403。
 *
 * <p><b>ride 兼容红线</b>：全部为新增端点，8 个冻结 OpenAPI 方法与 /openapi/** 一字未改；
 * ride 无签约 ⇒ 这些端点不被调用，零影响。
 */
@RestController
@RequestMapping("/api/channel")
public class SignController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference(check = false, timeout = 35000, retries = 0)
    private SignFacade signFacade;

    public SignController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- 协议生命周期 ----------------

    /** 签约（敏感：需二次认证）。POST /api/channel/sign */
    @PostMapping("/sign")
    public ResponseEntity<Object> sign(@RequestBody SignRequest request, HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        request.setOperator(operator);
        return GatewaySupport.of(signFacade.signContract(request));
    }

    @GetMapping("/sign/{signNo}")
    public ResponseEntity<Object> contract(@PathVariable String signNo) {
        return GatewaySupport.of(signFacade.getContract(signNo));
    }

    /** 协议列表：GET /api/channel/signs?accountId=&merchantId=&status= */
    @GetMapping("/signs")
    public ResponseEntity<Object> contracts(@RequestParam(required = false) String accountId,
                                            @RequestParam(required = false) String merchantId,
                                            @RequestParam(required = false) String status) {
        return GatewaySupport.of(signFacade.listContracts(accountId, merchantId, status));
    }

    /** 解约（敏感）。POST /api/channel/sign/{signNo}/unsign  body {requestId, reason} */
    @PostMapping("/sign/{signNo}/unsign")
    public ResponseEntity<Object> unsign(@PathVariable String signNo,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.unsignContract(signNo, text(body, "requestId"), operator,
                text(body, "reason")));
    }

    /** 授权额度视图（只读）：GET /api/channel/sign/{signNo}/quota */
    @GetMapping("/sign/{signNo}/quota")
    public ResponseEntity<Object> quota(@PathVariable String signNo) {
        return GatewaySupport.of(signFacade.getQuota(signNo));
    }

    @GetMapping("/sign/{signNo}/deducts")
    public ResponseEntity<Object> deducts(@PathVariable String signNo,
                                          @RequestParam(required = false) String status) {
        return GatewaySupport.of(signFacade.listDeducts(signNo, status));
    }

    /** 事件 outbox 留痕（SIGN_SUCCESS/SIGN_CANCEL 投递证据）。 */
    @GetMapping("/sign/{signNo}/events")
    public ResponseEntity<Object> events(@PathVariable String signNo) {
        return GatewaySupport.of(signFacade.listEvents(signNo));
    }

    @GetMapping("/sign/{signNo}/audit")
    public ResponseEntity<Object> audit(@PathVariable String signNo,
                                        @RequestParam(defaultValue = "50") int limit) {
        return GatewaySupport.of(signFacade.listAudit(signNo, limit));
    }

    // ---------------- 代扣执行 ----------------

    /** 代扣（敏感，资金动作）。POST /api/channel/agent-pay  body {signNo, amount, requestId, merchantId} */
    @PostMapping("/agent-pay")
    public ResponseEntity<Object> agentPay(@RequestBody AgentPayRequest request, HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        request.setOperator(operator);
        return GatewaySupport.of(signFacade.agentPay(request));
    }

    @GetMapping("/agent-pay/{deductNo}")
    public ResponseEntity<Object> deduct(@PathVariable String deductNo) {
        return GatewaySupport.of(signFacade.getDeduct(deductNo));
    }

    /** 手动重试（敏感）。POST /api/channel/agent-pay/{deductNo}/retry  body {requestId} */
    @PostMapping("/agent-pay/{deductNo}/retry")
    public ResponseEntity<Object> retry(@PathVariable String deductNo,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.retryDeduct(deductNo, text(body, "requestId"), operator));
    }

    /** 人工终止（敏感）。POST /api/channel/agent-pay/{deductNo}/terminate  body {reason} */
    @PostMapping("/agent-pay/{deductNo}/terminate")
    public ResponseEntity<Object> terminate(@PathVariable String deductNo,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.terminateDeduct(deductNo, operator, text(body, "reason")));
    }

    // ---------------- X-4 账户侧授权 ----------------

    /** 账户已签协议视图：GET /api/channel/authorizations?accountId= */
    @GetMapping("/authorizations")
    public ResponseEntity<Object> authorizations(@RequestParam String accountId) {
        return GatewaySupport.of(signFacade.listAuthorizations(accountId));
    }

    /** 账户侧撤销授权（敏感，归属校验在域内）。POST /api/channel/authorizations/{signNo}/revoke */
    @PostMapping("/authorizations/{signNo}/revoke")
    public ResponseEntity<Object> revoke(@PathVariable String signNo,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest http) {
        String operator = operator(http);
        if (operator == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.revokeAuthorization(text(body, "accountId"), signNo,
                text(body, "requestId"), operator));
    }

    // ---------------- 作业手动驱动（与 XXL/ppjob 同一实现，幂等） ----------------

    /** POST /api/channel/sign/jobs/retry?limit=50 —— 重试/收敛/终止/到期一次跑批。 */
    @PostMapping("/sign/jobs/retry")
    public ResponseEntity<Object> jobRetry(@RequestParam(defaultValue = "50") int limit, HttpServletRequest http) {
        if (operator(http) == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.runDeductRetry(limit));
    }

    /** POST /api/channel/sign/jobs/events?limit=50 —— 事件 outbox 投递兜底。 */
    @PostMapping("/sign/jobs/events")
    public ResponseEntity<Object> jobEvents(@RequestParam(defaultValue = "50") int limit, HttpServletRequest http) {
        if (operator(http) == null) {
            return denied();
        }
        return GatewaySupport.of(signFacade.runEventDispatch(limit));
    }

    // ---------------- 资金护航 / 审计 ----------------

    /** 二次认证：返回会话用户名（operator 恒取会话用户），缺失/失效返回 null。 */
    private String operator(HttpServletRequest request) {
        String validated = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (validated == null) {
            return null;
        }
        String session = AuditSupport.currentUsername();
        // token 已绑会话用户；会话缺失时以 token 内操作员为准（绝不接受 body 冒充）
        return session == null || session.isBlank() || "anonymous".equals(session) ? validated : session;
    }

    private static ResponseEntity<Object> denied() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                "code", "A0403"));
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString();
        return value.isBlank() ? null : value;
    }
}