package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.settle.FeeCalcRequest;
import com.qoder.payment.facade.settle.FeeRuleDTO;
import com.qoder.payment.facade.settle.SettleCycleDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.ShareSettleRequest;
import com.qoder.payment.facade.settle.WithdrawRequest;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.Method;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * P1 第二期 · 清结算工作流 REST（ST-1~ST-5，<b>独立新增控制器</b>，不改 ApiController/OpenApiController）。
 *
 * <p>薄适配层，业务经 Dubbo 路由到 settle 域；返回裸数据（与既有控制台风格一致）。
 *
 * <p><b>安全</b>：GET 由既有 SecurityConfig 规则 {@code GET /api/settle/** → settle:read} 覆盖；
 * POST 落到 {@code /api/** authenticated} 兜底（SecurityConfig 暂无 settle:write 权限点，
 * 需主会话补映射）。资金/费率/跑批三类敏感写操作在本控制器内<b>强制 X-Step-Up-Token</b>
 * （复用既有 {@link StepUpTokenStore}，口令再验走会话用户自己的凭据），缺失/失效 403。
 *
 * <p><b>ride 红线</b>：本控制器只挂 {@code /api/**}，{@code /openapi/**} 一字未改——
 * {@code fund.withdraw} 仍是同步资金单路径，无异步/WAITING/复核闸门。
 */
@RestController
@RequestMapping("/api/settle")
public class SettleOpsController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    // 清算跑批是长耗时 ops 操作（实测单日全量 10.9s，随存量增长），通用 8s 超时会把
    // 已在服务端正常执行的批次判成 C0001/503，连锁导致「已清算」边界断言全部失效。
    // 只给该方法放宽超时，其余方法仍用默认值。
    @DubboReference(methods = {
            @Method(name = "runSettleBatch", timeout = 120000)
    })
    private SettleFacade settleFacade;

    public SettleOpsController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- ST-1 T+N 结算 ----------------

    @GetMapping("/cycles")
    public ResponseEntity<Object> cycle(@RequestParam String merchantId) {
        return GatewaySupport.of(settleFacade.getCycle(merchantId));
    }

    @PostMapping("/cycles")
    public ResponseEntity<Object> saveCycle(@RequestBody SettleCycleDTO cycle) {
        return GatewaySupport.of(settleFacade.saveCycle(cycle));
    }

    @GetMapping("/batches")
    public ResponseEntity<Object> batches(@RequestParam(required = false) String merchantId,
                                          @RequestParam(required = false) Long bizDate) {
        return GatewaySupport.of(settleFacade.listBatches(merchantId, bizDate));
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<Object> batch(@PathVariable String id) {
        return GatewaySupport.of(settleFacade.getBatch(id));
    }

    /** 日切跑批（敏感：需二次认证）。body {bizDate?, shardIndex?, shardTotal?, force?}。 */
    @PostMapping("/batch/run")
    public ResponseEntity<Object> runBatch(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        Long bizDate = longOf(body, "bizDate");
        int shardIndex = intOf(body, "shardIndex", 0);
        int shardTotal = intOf(body, "shardTotal", 1);
        boolean force = boolOf(body, "force");
        return GatewaySupport.of(settleFacade.runSettleBatch(bizDate, shardIndex, shardTotal, force));
    }

    // ---------------- ST-2 费率配置 ----------------

    @GetMapping("/fee-rules")
    public ResponseEntity<Object> feeRules(@RequestParam(required = false) String merchantId) {
        return GatewaySupport.of(settleFacade.listFeeRules(merchantId));
    }

    /** 费率变更（敏感：需二次认证）；只影响新单，在途单用受理快照。 */
    @PostMapping("/fee-rules")
    public ResponseEntity<Object> saveFeeRule(@RequestBody FeeRuleDTO rule, HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(settleFacade.saveFeeRule(rule));
    }

    /** 计费预览（只算不落库，不需二次认证）。 */
    @PostMapping("/fee/calc")
    public ResponseEntity<Object> calcFee(@RequestBody FeeCalcRequest calcRequest) {
        return GatewaySupport.of(settleFacade.calcFee(calcRequest));
    }

    // ---------------- ST-3 分账（结算侧） ----------------

    @GetMapping("/shares")
    public ResponseEntity<Object> shares(@RequestParam String shareId) {
        return GatewaySupport.of(settleFacade.shares(shareId));
    }

    @GetMapping("/shares/summary")
    public ResponseEntity<Object> shareSummary(@RequestParam String shareId) {
        return GatewaySupport.of(settleFacade.shareSummary(shareId));
    }

    @PostMapping("/shares")
    public ResponseEntity<Object> recordShare(@RequestBody ShareSettleRequest shareRequest) {
        return GatewaySupport.of(settleFacade.recordShare(shareRequest));
    }

    @PostMapping("/shares/back")
    public ResponseEntity<Object> recordShareBack(@RequestBody ShareSettleRequest shareRequest) {
        return GatewaySupport.of(settleFacade.recordShareBack(shareRequest));
    }

    // ---------------- ST-4 提现到银行（沙箱） ----------------

    /** 出金受理（资金动账：需二次认证）。 */
    @PostMapping("/withdraw")
    public ResponseEntity<Object> withdraw(@RequestBody WithdrawRequest withdrawRequest, HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(settleFacade.withdraw(withdrawRequest));
    }

    @GetMapping("/withdraw/{id}")
    public ResponseEntity<Object> withdraw(@PathVariable String id) {
        return GatewaySupport.of(settleFacade.getWithdraw(id));
    }

    @PostMapping("/withdraw/{id}/reconcile")
    public ResponseEntity<Object> reconcile(@PathVariable String id) {
        return GatewaySupport.of(settleFacade.withdrawReconcile(id));
    }

    /** 沙箱银行回执（仅沙箱终态 SUCCESS/FAILED；幂等，终态只翻一次）。 */
    @PostMapping("/withdraw/{id}/receipt")
    public ResponseEntity<Object> receipt(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(settleFacade.withdrawReceipt(id, strOf(body, "status"), strOf(body, "reason")));
    }

    // ---------------- ST-5 商户账单/对账单 ----------------

    @GetMapping("/bills")
    public ResponseEntity<Object> bills(@RequestParam(required = false) String merchantId) {
        return GatewaySupport.of(settleFacade.listBills(merchantId));
    }

    @PostMapping("/bills")
    public ResponseEntity<Object> generateBill(@RequestBody Map<String, Object> body) {
        return GatewaySupport.of(settleFacade.generateBill(strOf(body, "merchantId"), longOf(body, "bizDate"),
                strOf(body, "billType")));
    }

    /** CSV 下载（明细 + 汇总）。 */
    @GetMapping("/bills/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var result = settleFacade.billCsv(id);
        if (!result.isSuccess()) {
            return ResponseEntity.status(GatewaySupport.statusOf(result.getCode()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"" + result.getMessage() + "\",\"code\":\"" + result.getCode() + "}")
                            .getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bill-" + id + ".csv\"")
                .body(result.getData().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- 支撑 ----------------

    /** 敏感写操作二次认证闸门（复用既有 StepUpTokenStore，不改既有拦截器/安全链）。 */
    private ResponseEntity<Object> requireStepUp(HttpServletRequest request) {
        String operator = stepUpTokenStore.validate(request.getHeader(STEP_UP_HEADER));
        if (operator == null) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）",
                    "code", "A0403"));
        }
        return null;
    }

    private static String strOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(Map<String, Object> body, String key, int def) {
        Long value = longOf(body, key);
        return value == null ? def : value.intValue();
    }

    private static boolean boolOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}