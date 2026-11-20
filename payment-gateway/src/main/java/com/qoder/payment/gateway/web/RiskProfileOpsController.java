package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.risk.AmlRuleDTO;
import com.qoder.payment.facade.risk.RiskAmlFacade;
import com.qoder.payment.facade.risk.RiskProfileFacade;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import com.qoder.payment.gateway.security.AuditSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * R-5 设备指纹/IP 画像 + R-6 AML 运营控制台端点（<b>新增独立文件，不改网关既有文件</b>）。
 *
 * <p><b>鉴权</b>：{@code /api/risk/**} 已被 SecurityConfig 既有规则覆盖
 * （GET→{@code risk:read}，POST/PUT/DELETE→{@code risk:write}），<b>无需新增权限点映射</b>；
 * 写操作在控制器内再校验 {@code risk:write}（ADMIN 通配 {@code *} 天然持有），越权 403 A0403。
 * <p><b>敏感操作二次认证</b>：AML 规则变更、STR 上报/归档、名单增删、留存期执行恒需
 * {@code X-Step-Up-Token}，且 token 归属必须等于当前会话用户；{@code operator} 恒取会话用户。
 * <p>{@code /openapi/**} 完全不受影响（§0.7 ride 兼容红线）。
 */
@Slf4j
@RestController
@RequestMapping("/api/risk")
public class RiskProfileOpsController {

    public static final String PERMISSION_WRITE = "risk:write";

    @DubboReference(check = false, timeout = 15000, retries = 0)
    private RiskProfileFacade riskProfileFacade;

    @DubboReference(check = false, timeout = 30000, retries = 0)
    private RiskAmlFacade riskAmlFacade;

    private final StepUpTokenStore stepUpTokenStore;

    public RiskProfileOpsController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- R-5 设备/IP/行为 ----------------

    @GetMapping("/device")
    public ResponseEntity<Object> device(@RequestParam String deviceId) {
        return unwrap(riskProfileFacade.getDeviceProfile(deviceId));
    }

    @GetMapping("/device/accounts")
    public ResponseEntity<Object> deviceAccounts(@RequestParam String deviceId) {
        return unwrap(riskProfileFacade.listDeviceAccounts(deviceId));
    }

    @GetMapping("/account/devices")
    public ResponseEntity<Object> accountDevices(@RequestParam String accountId) {
        return unwrap(riskProfileFacade.listAccountDevices(accountId));
    }

    @GetMapping("/ip")
    public ResponseEntity<Object> ip(@RequestParam String ip) {
        return unwrap(riskProfileFacade.getIpProfile(ip));
    }

    @GetMapping("/ip/sandbox")
    public ResponseEntity<Object> ipSandbox() {
        return unwrap(riskProfileFacade.ipSandbox());
    }

    @GetMapping("/behavior")
    public ResponseEntity<Object> behavior(@RequestParam(required = false) String payerId,
                                           @RequestParam(required = false) Integer limit) {
        return unwrap(riskProfileFacade.listBehavior(payerId, limit));
    }

    @GetMapping("/behavior/analyze")
    public ResponseEntity<Object> analyzeBehavior(@RequestParam(required = false) String payerId,
                                                  @RequestParam(required = false) String deviceId,
                                                  @RequestParam(required = false) Long windowMillis) {
        return unwrap(riskProfileFacade.analyzeBehavior(payerId, deviceId, windowMillis));
    }

    @GetMapping("/profile/status")
    public ResponseEntity<Object> profileStatus() {
        return unwrap(riskProfileFacade.profileStatus());
    }

    @PostMapping("/profile/retention")
    public ResponseEntity<Object> retention(@RequestBody(required = false) Map<String, Object> body,
                                            @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        Long before = null;
        if (body != null && body.get("beforeMillis") != null) {
            try {
                before = Long.valueOf(String.valueOf(body.get("beforeMillis")));
            } catch (Exception e) {
                log.warn("留存期参考时间非法（用当前时间）: {}", body.get("beforeMillis"));
            }
        }
        return unwrap(riskProfileFacade.applyRetention(sessionUser(), before));
    }

    // ---------------- R-6 AML 规则 ----------------

    @GetMapping("/aml/rules")
    public ResponseEntity<Object> amlRules(@RequestParam(required = false) String ruleType) {
        return unwrap(riskAmlFacade.listAmlRules(ruleType));
    }

    @PostMapping("/aml/rules")
    public ResponseEntity<Object> saveAmlRule(@RequestBody AmlRuleDTO rule,
                                              @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskAmlFacade.saveAmlRule(rule, sessionUser()));
    }

    @PostMapping("/aml/rules/{id}/disable")
    public ResponseEntity<Object> disableAmlRule(@PathVariable String id,
                                                 @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskAmlFacade.disableAmlRule(id, sessionUser()));
    }

    // ---------------- R-6 报告与状态机 ----------------

    @GetMapping("/aml/reports")
    public ResponseEntity<Object> amlReports(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String bizDate,
                                             @RequestParam(required = false) String payerId,
                                             @RequestParam(required = false) Integer limit) {
        return unwrap(riskAmlFacade.listAmlReports(status, bizDate, payerId, limit));
    }

    @GetMapping("/aml/reports/{id}")
    public ResponseEntity<Object> amlReport(@PathVariable long id) {
        return unwrap(riskAmlFacade.getAmlReport(id));
    }

    @PostMapping("/aml/reports/{id}/submit")
    public ResponseEntity<Object> submitAml(@PathVariable long id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskAmlFacade.submitAmlReport(id, sessionUser(), note(body)));
    }

    @PostMapping("/aml/reports/{id}/close")
    public ResponseEntity<Object> closeAml(@PathVariable long id,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskAmlFacade.closeAmlReport(id, sessionUser(), note(body)));
    }

    @GetMapping("/aml/reports/{id}/audit")
    public ResponseEntity<Object> amlAudit(@PathVariable long id) {
        return unwrap(riskAmlFacade.listAmlAudit(id));
    }

    // ---------------- R-6 监测与流水 ----------------

    @PostMapping("/aml/scan")
    public ResponseEntity<Object> amlScan(@RequestBody(required = false) Map<String, Object> body,
                                          @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        String bizDate = body == null || body.get("bizDate") == null ? null : String.valueOf(body.get("bizDate"));
        String payerId = body == null || body.get("payerId") == null ? null : String.valueOf(body.get("payerId"));
        return unwrap(riskAmlFacade.scanAml(bizDate, payerId));
    }

    @GetMapping("/aml/txns")
    public ResponseEntity<Object> amlTxns(@RequestParam(required = false) String payerId,
                                          @RequestParam(required = false) String bizDate,
                                          @RequestParam(required = false) Integer limit) {
        return unwrap(riskAmlFacade.listAmlTxns(payerId, bizDate, limit));
    }

    @GetMapping("/aml/status")
    public ResponseEntity<Object> amlStatus() {
        return unwrap(riskAmlFacade.amlStatus());
    }

    // ---------------- R-6 名单筛查（沙箱制裁/PEP） ----------------

    @GetMapping("/aml/watchlist")
    public ResponseEntity<Object> watchlist(@RequestParam(required = false) String listType) {
        return unwrap(riskAmlFacade.listWatchlist(listType));
    }

    @PostMapping("/aml/watchlist")
    public ResponseEntity<Object> addWatchlist(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        if (deny != null) {
            return deny;
        }
        return unwrap(riskAmlFacade.addWatchlistEntry(str(body, "listType"), str(body, "name"),
                str(body, "aliases"), str(body, "country"), sessionUser()));
    }

    @PostMapping("/aml/watchlist/{id}/remove")
    public ResponseEntity<Object> removeWatchlist(@PathVariable String id,
                                                  @RequestHeader(value = "X-Step-Up-Token", required = false) String token) {
        ResponseEntity<Object> deny = guard(token);
        return deny != null ? deny : unwrap(riskAmlFacade.removeWatchlistEntry(id, sessionUser()));
    }

    @PostMapping("/aml/screen")
    public ResponseEntity<Object> screen(@RequestBody Map<String, Object> body) {
        if (!hasWrite()) {
            return forbidden();
        }
        return unwrap(riskAmlFacade.screen(str(body, "subjectType"), str(body, "subject")));
    }

    @GetMapping("/aml/screenings")
    public ResponseEntity<Object> screenings(@RequestParam(required = false) String subject,
                                             @RequestParam(required = false) Integer limit) {
        return unwrap(riskAmlFacade.listScreenings(subject, limit));
    }

    // ---------------- 鉴权/响应 helpers ----------------

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
            log.warn("风控画像/AML 敏感操作拒绝：step-up token 归属与会话不符 session={} owner={}", user, owner);
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

    private static String str(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? null : String.valueOf(body.get(key));
    }

    private static String note(Map<String, Object> body) {
        String n = str(body, "note");
        return n == null ? str(body, "reason") : n;
    }

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