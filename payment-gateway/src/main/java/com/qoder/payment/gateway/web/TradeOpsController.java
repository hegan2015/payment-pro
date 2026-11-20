package com.qoder.payment.gateway.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P1 · G 泳道交易产品工作流 REST（T-3/T-1/T-2/T-4，<b>独立新增控制器</b>，
 * 不改 ApiController/OpenApiController 任何一行）。
 *
 * <p>薄适配层，业务经 Dubbo 路由到 trade 域；返回裸数据（与既有控制台风格一致）。
 *
 * <p><b>安全</b>：本控制器只挂 {@code /api/trade/**}，落既有 SecurityConfig 的
 * {@code /api/** authenticated} 兜底（ADMIN 持通配权限可直接调用；
 * 细粒度权限点 trade:read/trade:write/trade:expire 映射需主会话补 SecurityConfig）。
 * 资金打款（确认收货）、预授权完成/撤销、两类跑批触发均为敏感写操作，
 * 在本控制器内 <b>强制 X-Step-Up-Token</b>（复用既有 {@link StepUpTokenStore}，
 * 口令再验走会话用户自己的凭据），缺失/失效 403。
 *
 * <p><b>ride 红线</b>：{@code /openapi/**} 一字未改 —— {@code trade.create/pay/query} 仍是
 * INSTANT 默认产品同步路径（BALANCE 受理→PROCESSING→SUCCESS，无 WAITING/异步），
 * 手续费口径与 merchant+outTradeNo 幂等键不变。新产品只经本控制器/控制台显式 productType 进入。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeOpsController {

    private static final String STEP_UP_HEADER = "X-Step-Up-Token";

    private final StepUpTokenStore stepUpTokenStore;

    @DubboReference
    private TradeFacade tradeFacade;

    public TradeOpsController(StepUpTokenStore stepUpTokenStore) {
        this.stepUpTokenStore = stepUpTokenStore;
    }

    // ---------------- T-3 产品目录与路由参数 ----------------

    /** 产品目录（启用开关/状态机/状态集合/DynamicConfig 生效参数）。 */
    @GetMapping("/products")
    public ResponseEntity<Object> products() {
        return GatewaySupport.of(tradeFacade.listProducts());
    }

    // ---------------- T-1 担保交易 ----------------

    /** 确认收货打款卖家（敏感：需二次认证）。幂等 (ESCROW_CONFIRM,orderId) + escrow_status CAS。 */
    @PostMapping("/orders/{id}/confirm")
    public ResponseEntity<Object> confirmReceive(@PathVariable String id, HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(tradeFacade.confirmReceive(id));
    }

    /** 担保超时自动确认跑批（敏感：需二次认证）。body {shardIndex?, shardTotal?}，返回确认笔数。 */
    @PostMapping("/escrow/auto-confirm")
    public ResponseEntity<Object> autoConfirmEscrow(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(tradeFacade.autoConfirmEscrow(intOf(body, "shardIndex", 0), intOf(body, "shardTotal", 1)));
    }

    // ---------------- T-2 预授权 ----------------

    /** 预授权冻结（复用 account A-3 PREAUTH）。body {accountId, requestId?}。 */
    @PostMapping("/orders/{id}/preauth")
    public ResponseEntity<Object> preAuth(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return GatewaySupport.of(tradeFacade.preAuth(id, strOf(body, "accountId"), strOf(body, "requestId")));
    }

    /** 预授权完成扣款（敏感：需二次认证）。body {amount, requestId}，amount ≤ 剩余冻结额。 */
    @PostMapping("/orders/{id}/preauth/complete")
    public ResponseEntity<Object> completePreAuth(@PathVariable String id, @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        Long amount = longOf(body, "amount");
        if (amount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount 必填", "code", "A0400"));
        }
        return GatewaySupport.of(tradeFacade.completePreAuth(id, amount, strOf(body, "requestId")));
    }

    /** 预授权撤销（敏感：需二次认证）。body {requestId?}，剩余冻结额全额解冻。 */
    @PostMapping("/orders/{id}/preauth/cancel")
    public ResponseEntity<Object> cancelPreAuth(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(tradeFacade.cancelPreAuth(id, strOf(body, "requestId")));
    }

    // ---------------- T-4 超时自动关闭 ----------------

    /** 未支付订单超时关闭跑批（敏感：需二次认证）。body {shardIndex?, shardTotal?}，返回净关闭笔数。 */
    @PostMapping("/expire/run")
    public ResponseEntity<Object> closeExpired(@RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        ResponseEntity<Object> denied = requireStepUp(request);
        if (denied != null) {
            return denied;
        }
        return GatewaySupport.of(tradeFacade.closeExpiredOrders(intOf(body, "shardIndex", 0), intOf(body, "shardTotal", 1)));
    }

    /** 到期候选预览（只读，不关闭）：供运维/验证分片不重不漏。 */
    @GetMapping("/expire/due")
    public ResponseEntity<Object> due(@RequestParam(defaultValue = "0") int shardIndex,
                                      @RequestParam(defaultValue = "1") int shardTotal) {
        // 只读预览：绝不调用关闭入口（不动任何状态），按 orderId CRC32 与作业同一分片算法切片
        long now = System.currentTimeMillis();
        java.util.List<com.qoder.payment.facade.trade.OrderDTO> due = tradeFacade.listOrders().orThrow().stream()
                .filter(o -> "CREATED".equals(o.getStatus()) && o.getExpireAt() != null && o.getExpireAt() <= now)
                .filter(o -> shardTotal <= 1
                        || com.qoder.payment.common.db.ShardKeys.shardOf(o.getId(), shardTotal) == shardIndex)
                .toList();
        return GatewaySupport.of(com.qoder.payment.common.result.Result.ok(due));
    }

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
}