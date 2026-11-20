package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.LedgerQuery;
import com.qoder.payment.facade.accounting.VoucherQuery;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P1 第二期 · E 泳道账务工作流 REST（AC-2 科目 / AC-3 日切日终 / AC-4 账簿凭证 / AC-5 账实核对，
 * <b>独立新增控制器</b>，不改 ApiController / OpenApiController / ReserveController）。
 *
 * <p>薄适配层，业务经 Dubbo 路由到 accounting 域；返回裸数据（与既有控制台风格一致）。
 *
 * <p><b>安全</b>：GET 由既有 SecurityConfig 规则 {@code GET /api/accounting/** → accounting:read} 覆盖；
 * POST 落到 {@code /api/** authenticated} 兜底（SecurityConfig 暂无 accounting:daycut / accounting:subject
 * 权限点，<b>需主会话补映射</b>）。日切/日终跑批/科目改挂/账实核对四类敏感写操作在本控制器内
 * <b>强制 X-Step-Up-Token</b>（复用既有 {@link StepUpTokenStore}，口令再验走会话用户自己的凭据），
 * 缺失/失效 403。
 *
 * <p><b>凭证只读红线</b>：本控制器只提供凭证/账簿<b>查询</b>，无任何修改入口（VoucherGuard）。
 * <p><b>ride 红线</b>：只挂 {@code /api/**}，{@code /openapi/**} 一字未改；
 * {@code account.balance} 仍返回裸数字 long，手续费/幂等键/BALANCE 同步路径不变。
 */
@RestController
@RequestMapping("/api/accounting")
public class LedgerController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private AccountingFacade accountingFacade;

    public LedgerController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- AC-2 会计科目体系 ----------------

    @GetMapping("/subjects")
    public ResponseEntity<Object> subjects() {
        return GatewaySupport.of(accountingFacade.listSubjects());
    }

    @GetMapping("/subject-balance")
    public ResponseEntity<Object> subjectBalance(@RequestParam(required = false) String subjectCode,
                                                 @RequestParam(required = false) Long bizDate) {
        return GatewaySupport.of(accountingFacade.listSubjectBalance(subjectCode, bizDate));
    }

    @GetMapping("/subjects/{code}/balance")
    public ResponseEntity<Object> oneSubjectBalance(@PathVariable String code,
                                                    @RequestParam(required = false) Long bizDate) {
        return GatewaySupport.of(accountingFacade.getSubjectBalance(code, bizDate));
    }

    @GetMapping("/accounts/{id}/subject")
    public ResponseEntity<Object> subjectOfAccount(@PathVariable String id) {
        return GatewaySupport.of(accountingFacade.subjectOfAccount(id));
    }

    /** 账户科目改挂（敏感：需二次认证）。系统户固定映射不可改；只改元数据不动余额。 */
    @PostMapping("/subject-mapping")
    public ResponseEntity<Object> mapSubject(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(accountingFacade.mapAccountSubject(str(body, "accountId"), str(body, "subjectCode")));
    }

    // ---------------- AC-3 日切 + 日终批处理 ----------------

    @GetMapping("/day")
    public ResponseEntity<Object> currentDay() {
        return GatewaySupport.of(accountingFacade.currentAccountingDay());
    }

    @GetMapping("/days")
    public ResponseEntity<Object> days(@RequestParam(defaultValue = "10") int limit) {
        return GatewaySupport.of(accountingFacade.listAccountingDays(limit));
    }

    /** 日切（敏感：需二次认证）。body {bizDate?, shardIndex?, shardTotal?}；幂等 + CAS + 分布式锁。 */
    @PostMapping("/day-cut")
    public ResponseEntity<Object> dayCut(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(accountingFacade.runDayCut(longOf(body, "bizDate"),
                intOf(body, "shardIndex", 0), intOf(body, "shardTotal", 1)));
    }

    /** 日终批（敏感：需二次认证）。body {bizDate?, shardIndex?, shardTotal?}；失败可重跑幂等。 */
    @PostMapping("/day-end")
    public ResponseEntity<Object> dayEnd(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(accountingFacade.runDayEnd(longOf(body, "bizDate"),
                intOf(body, "shardIndex", 0), intOf(body, "shardTotal", 1)));
    }

    @GetMapping("/day-end-report")
    public ResponseEntity<Object> dayEndReport(@RequestParam(required = false) Long bizDate) {
        return GatewaySupport.of(accountingFacade.getDayEndReport(bizDate));
    }

    // ---------------- AC-4 账簿/凭证查询（只读） ----------------

    @GetMapping("/general-ledger")
    public ResponseEntity<Object> generalLedger(@RequestParam(required = false) String subjectCode,
                                                @RequestParam(required = false) Long bizDate,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return GatewaySupport.of(accountingFacade.generalLedger(subjectCode, bizDate, page, size));
    }

    @GetMapping("/detail-ledger")
    public ResponseEntity<Object> detailLedger(@RequestParam(required = false) String accountId,
                                               @RequestParam(required = false) String subjectCode,
                                               @RequestParam(required = false) String bizNo,
                                               @RequestParam(required = false) String refId,
                                               @RequestParam(required = false) String dir,
                                               @RequestParam(required = false) Long bizDate,
                                               @RequestParam(required = false) Long dateFrom,
                                               @RequestParam(required = false) Long dateTo,
                                               @RequestParam(required = false) String currency,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        LedgerQuery query = new LedgerQuery();
        query.setAccountId(accountId);
        query.setSubjectCode(subjectCode);
        query.setBizNo(bizNo);
        query.setRefId(refId);
        query.setDir(dir);
        query.setBizDate(bizDate);
        query.setDateFrom(dateFrom);
        query.setDateTo(dateTo);
        query.setCurrency(currency);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(accountingFacade.detailLedger(query));
    }

    @GetMapping("/balance-sheet")
    public ResponseEntity<Object> balanceSheet(@RequestParam(required = false) Long bizDate) {
        return GatewaySupport.of(accountingFacade.balanceSheet(bizDate));
    }

    @GetMapping("/vouchers")
    public ResponseEntity<Object> vouchers(@RequestParam(required = false) String bizNo,
                                           @RequestParam(required = false) String refId,
                                           @RequestParam(required = false) String subjectCode,
                                           @RequestParam(required = false) String accountId,
                                           @RequestParam(required = false) String voucherType,
                                           @RequestParam(required = false) Long bizDate,
                                           @RequestParam(required = false) Long dateFrom,
                                           @RequestParam(required = false) Long dateTo,
                                           @RequestParam(defaultValue = "true") boolean withEntries,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        VoucherQuery query = new VoucherQuery();
        query.setBizNo(bizNo);
        query.setRefId(refId);
        query.setSubjectCode(subjectCode);
        query.setAccountId(accountId);
        query.setVoucherType(voucherType);
        query.setBizDate(bizDate);
        query.setDateFrom(dateFrom);
        query.setDateTo(dateTo);
        query.setWithEntries(withEntries);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(accountingFacade.vouchers(query));
    }

    @GetMapping("/vouchers/{bizNo}")
    public ResponseEntity<Object> voucher(@PathVariable String bizNo) {
        return GatewaySupport.of(accountingFacade.voucher(bizNo));
    }

    // ---------------- AC-5 账实核对 ----------------

    /** 账实核对（敏感：需二次认证，会建差错单）。body {bizDate?, channels?}。 */
    @PostMapping("/reserve-recon")
    public ResponseEntity<Object> reserveRecon(@RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(accountingFacade.reserveRecon(longOf(body, "bizDate"), strList(body, "channels")));
    }

    @GetMapping("/reserve-recon/logs")
    public ResponseEntity<Object> reserveReconLogs(@RequestParam(required = false) Long bizDate,
                                                   @RequestParam(required = false) String channelCode,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return GatewaySupport.of(accountingFacade.reserveReconLogs(bizDate, channelCode, limit));
    }

    // ---------------- 工具 ----------------

    private ResponseEntity<Object> requireStepUp(HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (operator == null) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                    "code", "A0403"));
        }
        return null;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(Map<String, Object> body, String key, int def) {
        Long v = longOf(body, key);
        return v == null ? def : v.intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else {
            for (String part : String.valueOf(v).split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out.isEmpty() ? null : out;
    }
}