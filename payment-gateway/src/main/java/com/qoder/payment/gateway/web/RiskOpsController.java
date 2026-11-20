package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.QuotaRuleDTO;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.facade.risk.RiskRuleFacade;
import com.qoder.payment.facade.risk.RiskThresholdDTO;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import com.qoder.payment.gateway.security.AuditSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R-1~R-4 风控运营控制台端点（<b>新增独立文件，不改网关既有文件</b>）。
 *
 * <p><b>鉴权</b>：{@code /api/risk/**} 由 SecurityConfig 兜底规则覆盖 → 未登录 401；
 * 写操作在控制器内校验权限点 {@code risk:write}（ADMIN 通配 {@code *} 天然持有），越权 403 A0403。
 * <p><b>敏感操作二次认证</b>：规则发布/停用/影子/回滚、全局影子开关、名单增删、阈值变更、人审裁决
 * 恒需 {@code X-Step-Up-Token}（{@code POST /api/security/step-up} 口令再验颁发），
 * 且 token 归属必须等于<b>当前会话用户</b>；{@code operator} 恒取会话用户，杜绝冒充他人自审自批。
 * <p>{@code /openapi/**} 完全不受影响（§0.7 ride 兼容红线）。
 */
@Slf4j
@RestController
@RequestMapping("/api/risk")
public class RiskOpsController {

    public static final String PERMISSION_WRITE = "risk:write";

    @DubboReference(check = false, timeout = 10000, retries = 0)
    private RiskRuleFacade riskRuleFacade;

    private final StepUpTokenStore stepUpTokenStore;

    public RiskOpsController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- R-1 规则 ----------------

    @GetMapping("/rules")
    public ResponseEntity<Object> rules(@RequestParam(required = false) String scene) {
        return unwrap(riskRuleFacade.listRules(scene));
    }

    @PostMapping("/rules")
    public ResponseEntity<Object> saveRule(@RequestBody RiskRuleDTO rule,
                                           @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.saveRule(rule, sessionUser()));
    }

    @PostMapping("/rules/{id}/publish")
    public ResponseEntity<Object> publish(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                          @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        Integer gray = body == null || body.get("grayPercent") == null ? null
                : Integer.valueOf(String.valueOf(body.get("grayPercent")));
        String whitelist = body == null || body.get("whitelist") == null ? null : String.valueOf(body.get("whitelist"));
        return unwrap(riskRuleFacade.publishRule(id, gray, whitelist, sessionUser()));
    }

    @PostMapping("/rules/{id}/disable")
    public ResponseEntity<Object> disable(@PathVariable String id,
                                          @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.disableRule(id, sessionUser()));
    }

    @PostMapping("/rules/{id}/shadow")
    public ResponseEntity<Object> shadow(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        boolean on = body == null || body.get("on") == null || Boolean.parseBoolean(String.valueOf(body.get("on")));
        return unwrap(riskRuleFacade.shadowMode(id, on, sessionUser()));
    }

    @PostMapping("/rules/rollback")
    public ResponseEntity<Object> rollbackRules(@RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.rollbackRules(sessionUser()));
    }

    @PostMapping("/shadow")
    public ResponseEntity<Object> globalShadow(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        boolean on = body != null && Boolean.parseBoolean(String.valueOf(body.get("on")));
        return unwrap(riskRuleFacade.globalShadow(on, sessionUser()));
    }

    @PostMapping("/reload")
    public ResponseEntity<Object> reload() {
        if (!hasWrite()) {
            return forbidden();
        }
        return unwrap(riskRuleFacade.reload());
    }

    @GetMapping("/engine")
    public ResponseEntity<Object> engine() {
        return unwrap(riskRuleFacade.engineStatus());
    }

    @GetMapping("/versions")
    public ResponseEntity<Object> versions(@RequestParam(required = false) String kind,
                                           @RequestParam(required = false) Integer limit) {
        return unwrap(riskRuleFacade.listVersions(kind, limit));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Object> evaluate(@RequestBody RiskCheckRequest request) {
        return unwrap(riskRuleFacade.evaluate(request));
    }

    // ---------------- R-2 限额 ----------------

    @GetMapping("/quotas")
    public ResponseEntity<Object> quotas() {
        return unwrap(riskRuleFacade.listQuotas());
    }

    @PostMapping("/quotas")
    public ResponseEntity<Object> saveQuota(@RequestBody QuotaRuleDTO quota,
                                            @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.saveQuota(quota, sessionUser()));
    }

    @PostMapping("/quotas/{id}/disable")
    public ResponseEntity<Object> disableQuota(@PathVariable String id,
                                               @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.disableQuota(id, sessionUser()));
    }

    @PostMapping("/quotas/rollback")
    public ResponseEntity<Object> rollbackQuotas(@RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.rollbackQuotas(sessionUser()));
    }

    @GetMapping("/quotas/usage")
    public ResponseEntity<Object> quotaUsage(@RequestParam(required = false) String keyPrefix) {
        return unwrap(riskRuleFacade.quotaUsage(keyPrefix));
    }

    // ---------------- R-3 名单 ----------------

    @GetMapping("/lists")
    public ResponseEntity<Object> lists(@RequestParam(required = false) String valueType,
                                        @RequestParam(required = false) String value) {
        return unwrap(riskRuleFacade.listEntries(valueType, value));
    }

    @PostMapping("/lists")
    public ResponseEntity<Object> addList(@RequestBody ListEntryDTO entry,
                                          @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.addListEntry(entry, sessionUser()));
    }

    @DeleteMapping("/lists/{id}")
    public ResponseEntity<Object> removeList(@PathVariable String id,
                                             @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.removeListEntry(id, sessionUser()));
    }

    @PostMapping("/lists/rollback")
    public ResponseEntity<Object> rollbackLists(@RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.rollbackLists(sessionUser()));
    }

    @GetMapping("/lists/hit")
    public ResponseEntity<Object> hitList(@RequestParam String valueType, @RequestParam String value) {
        return unwrap(riskRuleFacade.hitList(valueType, value));
    }

    // ---------------- R-4 评分/处置 ----------------

    @GetMapping("/thresholds")
    public ResponseEntity<Object> thresholds(@RequestParam(required = false) String scene) {
        return unwrap(riskRuleFacade.getThresholds(scene));
    }

    @PostMapping("/thresholds")
    public ResponseEntity<Object> setThresholds(@RequestBody RiskThresholdDTO thresholds,
                                                @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.setThresholds(thresholds, sessionUser()));
    }

    @PostMapping("/thresholds/rollback")
    public ResponseEntity<Object> rollbackThresholds(@RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskRuleFacade.rollbackThresholds(sessionUser()));
    }

    @GetMapping("/decisions")
    public ResponseEntity<Object> decisions(@RequestParam(required = false) String payerId,
                                            @RequestParam(required = false) Integer limit) {
        return unwrap(riskRuleFacade.listDecisions(payerId, limit));
    }

    @GetMapping("/decisions/{id}")
    public ResponseEntity<Object> decision(@PathVariable long id) {
        return unwrap(riskRuleFacade.getDecision(id));
    }

    @PostMapping("/decisions/{id}/review")
    public ResponseEntity<Object> review(@PathVariable long id, @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        boolean approve = body != null && Boolean.parseBoolean(String.valueOf(body.get("approve")));
        return unwrap(riskRuleFacade.reviewDecision(id, approve, sessionUser()));
    }

    @GetMapping("/alerts")
    public ResponseEntity<Object> alerts(@RequestParam(required = false) Integer limit) {
        return unwrap(riskRuleFacade.listAlerts(limit));
    }

    // ---------------- 鉴权/响应 helpers ----------------

    /** 写操作守卫：权限点 + 二次认证（token 归属必须等于会话用户）。 */
    private ResponseEntity<Object> guard(String stepUpToken) {
        if (!hasWrite()) {
            return forbidden();
        }
        String user = sessionUser();
        String owner = stepUpTokenStore.validate(stepUpToken);
        if (owner == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "敏感操作需二次认证（X-Step-Up-Token 缺失或已失效）", "code", "A0403"));
        }
        if (!owner.equals(user)) {
            log.warn("风控敏感操作拒绝：step-up token 归属与会话不符 session={} owner={}", user, owner);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "二次认证凭据不属于当前登录账号", "code", "A0403"));
        }
        return null;
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "无权限执行该操作", "code", "A0403"));
    }

    private static boolean hasWrite() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
                PERMISSION_WRITE.equals(a.getAuthority()) || "*".equals(a.getAuthority()));
    }

    private static String sessionUser() {
        String u = AuditSupport.currentUsername();
        return u == null || u.isBlank() ? "anonymous" : u;
    }

    /** Result → HTTP（与网关既有风格一致：成功返回裸数据，失败 {"error","code"}）。 */
    private static ResponseEntity<Object> unwrap(Result<?> result) {
        if (result == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "风控域不可用", "code", "C0001"));
        }
        if (result.isSuccess()) {
            Object data = result.getData();
            return ResponseEntity.ok(data == null ? new LinkedHashMap<String, Object>() : data);
        }
        String code = result.getCode();
        HttpStatus status = switch (code == null ? "" : code) {
            case "A0400" -> HttpStatus.BAD_REQUEST;
            case "A0403" -> HttpStatus.FORBIDDEN;
            case "A0404" -> HttpStatus.NOT_FOUND;
            case "A0409" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", result.getMessage());
        body.put("code", code);
        return ResponseEntity.status(status).body(body);
    }
}