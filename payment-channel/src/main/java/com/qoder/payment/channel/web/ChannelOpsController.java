package com.qoder.payment.channel.web;

import com.qoder.payment.channel.bill.ChannelBillService;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.channel.mapper.SandboxOrderMapper;
import com.qoder.payment.channel.route.ChannelMetricsCollector;
import com.qoder.payment.channel.route.ChannelRouter;
import com.qoder.payment.channel.service.ChannelConfigService;
import com.qoder.payment.channel.service.ChannelLimitService;
import com.qoder.payment.channel.service.ChannelQueryService;
import com.qoder.payment.channel.service.ChannelService;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.channel.spi.ChannelFaultInjector;
import com.qoder.payment.channel.spi.ChannelHttpClient;
import com.qoder.payment.channel.spi.SandboxBankAdapter;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.job.JobHandlerRegistry;
import com.qoder.payment.common.job.JobResult;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import com.qoder.payment.facade.channel.ChannelTccContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CH-2a/2b/3/4/5 渠道域内部运维面（端口 18087，<b>不经网关暴露</b>，与 actuator 同端口）：
 * SPI 直探、路由/限额/维护观测与设置、故障注入、沙箱渠道侧下单、作业手动触发。
 *
 * <p>用途：验收脚本（verify-channel.py）与运维排障。控制台/商户面走网关
 * {@code ChannelOpsController}（Bearer + RBAC），本面只在内网可达。
 *
 * <p><b>数据保护</b>：本控制器只写渠道域自有新表（channel_config / channel_daily_usage /
 * sandbox_order）与显式传入的测试业务号流水，绝不 UPDATE/DELETE 既有支付/账务/对账数据。
 */
@Slf4j
@RestController
@RequestMapping("/channel-ops")
@RequiredArgsConstructor
public class ChannelOpsController {

    private final ChannelAdapterRegistry registry;
    private final ChannelConfigService configService;
    private final ChannelLimitService limitService;
    private final ChannelRouter router;
    private final ChannelMetricsCollector metrics;
    private final ChannelFaultInjector faults;
    private final ChannelService channelService;
    private final ChannelQueryService queryService;
    private final ChannelTradeMapper tradeMapper;
    private final SandboxOrderMapper sandboxOrderMapper;
    private final ChannelHttpClient http;
    private final DynamicConfig config;
    private final JobHandlerRegistry jobRegistry;
    private final ChannelBillService billService;
    private final com.qoder.payment.channel.route.RouteRuleConfig routeRules;

    @org.springframework.beans.factory.annotation.Value("${payment.channel.async:false}")
    private boolean async;

    // ---------- SPI 观测与直探 ----------

    @GetMapping("/adapters")
    public ResponseEntity<Object> adapters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChannelAdapter a : registry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelCode", a.channelCode());
            item.put("adapterClass", a.getClass().getSimpleName());
            item.put("adapterType", a.adapterType());
            item.put("env", a.env());
            item.put("asyncCapable", a.asyncCapable());
            item.put("billSupported", a.billSupported());
            ChannelConfigDO c = configService.get(a.channelCode());
            item.put("configStatus", c == null ? "UNCONFIGURED" : c.getStatus());
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    /** SPI 直探：不经路由/不落本地流水，只调适配器（可插拔与故障注入断言用）。 */
    @PostMapping("/spi/pay")
    public ResponseEntity<Object> spiPay(@RequestBody Map<String, Object> body) {
        String code = str(body, "channelCode");
        ChannelAdapter adapter = registry.find(code);
        if (adapter == null) {
            return ChannelOpsSupport.badRequest("渠道适配器未注册: " + code);
        }
        String type = strOr(body, "type", "PAY");
        try {
            ChannelResult result;
            if ("REFUND".equals(type)) {
                ChannelRefundRequest request = new ChannelRefundRequest();
                request.setBizNo(str(body, "bizNo"));
                request.setOrigBizNo(str(body, "origBizNo"));
                request.setCardNo(str(body, "cardNo"));
                request.setAmount(lng(body, "amount"));
                request.setChannelCode(code);
                result = adapter.refund(request);
            } else {
                ChannelPayRequest request = new ChannelPayRequest();
                request.setBizNo(str(body, "bizNo"));
                request.setCardNo(str(body, "cardNo"));
                request.setAmount(lng(body, "amount"));
                request.setChannelCode(code);
                result = adapter.pay(request);
            }
            return ResponseEntity.ok(result);
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @GetMapping("/spi/query")
    public ResponseEntity<Object> spiQuery(@RequestParam String channelCode, @RequestParam String bizNo) {
        ChannelAdapter adapter = registry.find(channelCode);
        if (adapter == null) {
            return ChannelOpsSupport.badRequest("渠道适配器未注册: " + channelCode);
        }
        return ResponseEntity.ok(adapter.query(bizNo));
    }

    @GetMapping("/trades/{bizNo}/side")
    public ResponseEntity<Object> tradeSide(@PathVariable String bizNo) {
        try {
            return ResponseEntity.ok(queryService.queryChannelSide(bizNo));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    // ---------- 渠道级受理（SPI + 路由 + 限额全链路，测试业务号） ----------

    @PostMapping("/trade/accept")
    public ResponseEntity<Object> accept(@RequestBody Map<String, Object> body) {
        ChannelTccContext ctx = ctx(body);
        String type = strOr(body, "type", "PAY");
        try {
            channelService.preauth(ctx, type);
            return ResponseEntity.ok(queryService.queryChannelTrade(ctx.getBizNo()));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/trade/capture")
    public ResponseEntity<Object> capture(@RequestBody Map<String, Object> body) {
        ChannelTccContext ctx = ctx(body);
        try {
            channelService.capture(ctx, strOr(body, "type", "PAY"));
            return ResponseEntity.ok(queryService.queryChannelTrade(ctx.getBizNo()));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/trade/void")
    public ResponseEntity<Object> voidAuth(@RequestBody Map<String, Object> body) {
        ChannelTccContext ctx = ctx(body);
        channelService.voidAuth(ctx);
        ChannelTradeDO trade = tradeMapper.selectByBizNo(ctx.getBizNo());
        return ResponseEntity.ok(trade == null ? Map.of("bizNo", ctx.getBizNo(), "voided", false)
                : ChannelService.toDTO(trade));
    }

    // ---------- 路由 / 指标 ----------

    @GetMapping("/route")
    public ResponseEntity<Object> route(@RequestParam(required = false) Long amount,
                                        @RequestParam(required = false) String channelCode,
                                        @RequestParam(required = false) String cardNo,
                                        @RequestParam(required = false) String cardBin,
                                        @RequestParam(required = false) String type) {
        ChannelRouteRequest request = new ChannelRouteRequest();
        request.setAmount(amount);
        request.setChannelCode(channelCode);
        request.setCardNo(cardNo);
        request.setCardBin(cardBin);
        request.setType(type);
        return ResponseEntity.ok(router.route(request));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> metrics() {
        List<Object> out = new ArrayList<>();
        for (ChannelConfigDO c : configService.list()) {
            out.add(metrics.snapshot(c.getChannelCode()));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/metrics/{code}/breaker")
    public ResponseEntity<Object> breaker(@PathVariable String code, @RequestParam(defaultValue = "10000") long ms) {
        metrics.tripBreaker(code, ms);
        return ResponseEntity.ok(metrics.snapshot(code));
    }

    @PostMapping("/metrics/{code}/reset")
    public ResponseEntity<Object> resetMetrics(@PathVariable String code) {
        metrics.reset(code);
        return ResponseEntity.ok(metrics.snapshot(code));
    }

    // ---------- 配置 / 限额 / 维护 ----------

    @GetMapping("/configs")
    public ResponseEntity<Object> configs() {
        return ResponseEntity.ok(configService.list());
    }

    @PostMapping("/config/refresh")
    public ResponseEntity<Object> refreshConfig() {
        boolean reloaded = config.refresh();
        return ResponseEntity.ok(Map.of("provider", config.provider(), "reloaded", reloaded,
                "snapshotKeys", config.snapshot().keySet()));
    }

    @GetMapping("/limit/{code}")
    public ResponseEntity<Object> limit(@PathVariable String code) {
        try {
            return ResponseEntity.ok(limitService.view(code));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/limit/{code}")
    public ResponseEntity<Object> setLimit(@PathVariable String code,
                                           @RequestParam(required = false) Long single,
                                           @RequestParam(required = false) Long daily) {
        try {
            configService.setLimits(code, single, daily);
            return ResponseEntity.ok(limitService.view(code));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/maintenance/{code}")
    public ResponseEntity<Object> maintenance(@PathVariable String code,
                                              @RequestParam(required = false) Long start,
                                              @RequestParam(required = false) Long end) {
        try {
            configService.setMaintenance(code, start, end);
            return ResponseEntity.ok(limitService.view(code));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/status/{code}")
    public ResponseEntity<Object> status(@PathVariable String code, @RequestParam String status) {
        try {
            configService.setStatus(code, status);
            return ResponseEntity.ok(limitService.view(code));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/score/{code}")
    public ResponseEntity<Object> score(@PathVariable String code,
                                        @RequestParam(required = false) Integer weight,
                                        @RequestParam(required = false) Integer feeBps) {
        try {
            configService.setScoreFactors(code, weight, feeBps);
            return ResponseEntity.ok(configService.require(code));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    // ---------- 故障注入（沙箱 + 账单） ----------

    @GetMapping("/faults")
    public ResponseEntity<Object> faults() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delayMs", faults.getDelayMs());
        out.put("reject", faults.isReject());
        out.put("receiptLoss", faults.isReceiptLoss());
        out.put("amountMismatch", faults.isAmountMismatch());
        out.put("amountDelta", faults.getAmountDelta());
        out.put("down", faults.isDown());
        out.put("timeoutMs", faults.getTimeoutMs());
        out.put("targetBizNo", faults.getTargetBizNo());
        out.put("billFaults", faults.getBillFaults());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/faults")
    public ResponseEntity<Object> setFaults(@RequestBody Map<String, Object> body) {
        if (body.containsKey("delayMs")) {
            faults.setDelayMs(lng(body, "delayMs"));
        }
        if (body.containsKey("reject")) {
            faults.setReject(bool(body, "reject"));
        }
        if (body.containsKey("receiptLoss")) {
            faults.setReceiptLoss(bool(body, "receiptLoss"));
        }
        if (body.containsKey("amountMismatch")) {
            faults.setAmountMismatch(bool(body, "amountMismatch"));
        }
        if (body.containsKey("amountDelta")) {
            faults.setAmountDelta(lngOr(body, "amountDelta", 100L));
        }
        if (body.containsKey("down")) {
            faults.setDown(bool(body, "down"));
        }
        if (body.containsKey("timeoutMs")) {
            faults.setTimeoutMs(lngOr(body, "timeoutMs", 0L));
        }
        if (body.containsKey("targetBizNo")) {
            faults.setTargetBizNo(str(body, "targetBizNo"));
        }
        return faults();
    }

    @PostMapping("/faults/reset")
    public ResponseEntity<Object> resetFaults() {
        faults.reset();
        return faults();
    }

    @PostMapping("/faults/bill/{code}")
    public ResponseEntity<Object> billFault(@PathVariable String code, @RequestBody Map<String, Object> body) {
        ChannelFaultInjector.BillFault fault = faults.billFaultOrCreate(code);
        if (body.containsKey("amountBizNo")) {
            fault.setAmountBizNo(str(body, "amountBizNo"));
        }
        if (body.containsKey("amountDelta")) {
            fault.setAmountDelta(lngOr(body, "amountDelta", 0L));
        }
        if (body.containsKey("dropBizNo")) {
            fault.setDropBizNo(str(body, "dropBizNo"));
        }
        if (body.containsKey("totalDelta")) {
            fault.setTotalDelta(lngOr(body, "totalDelta", 0L));
        }
        if (body.containsKey("extraRows")) {
            Object rows = body.get("extraRows");
            fault.getExtraRows().clear();
            if (rows instanceof List<?> list) {
                for (Object o : list) {
                    fault.getExtraRows().add(String.valueOf(o));
                }
            }
        }
        return ResponseEntity.ok(fault);
    }

    @PostMapping("/faults/bill/{code}/reset")
    public ResponseEntity<Object> resetBillFault(@PathVariable String code) {
        faults.resetBill(code);
        return ResponseEntity.ok(Map.of("channelCode", code, "cleared", true));
    }

    // ---------- 沙箱渠道侧（模拟银行） ----------

    /** 以渠道客户端身份向沙箱银行下单（真实 HTTP + 签名），用于渠道侧账本/账单差异构造。 */
    @PostMapping("/sandbox/order")
    public ResponseEntity<Object> sandboxOrder(@RequestBody Map<String, Object> body) {
        String bizNo = str(body, "bizNo");
        if (bizNo == null || bizNo.isBlank()) {
            return ChannelOpsSupport.badRequest("bizNo 必填");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bizNo", bizNo);
        payload.put("type", strOr(body, "type", "PAY"));
        payload.put("amount", lngOr(body, "amount", 1000L));
        payload.put("cardNo", strOr(body, "cardNo", "6222020202020202"));
        payload.put("origBizNo", str(body, "origBizNo"));
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            return ChannelOpsSupport.badRequest("报文序列化失败: " + e.getMessage());
        }
        ChannelHttpClient.HttpResult result = http.postJson(baseUrl() + "/sandbox-bank/submit", json,
                SandboxBankAdapter.SECRET_REF, 5000L);
        if (!result.ok()) {
            return ResponseEntity.status(result.status() == 0 ? 502 : result.status())
                    .body(Map.of("error", "沙箱下单失败: " + (result.error() == null ? result.body() : result.error())));
        }
        return ResponseEntity.ok(result.body());
    }

    @GetMapping("/sandbox/orders")
    public ResponseEntity<Object> sandboxOrders(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(sandboxOrderMapper.selectRecent(Math.max(1, Math.min(limit, 200))));
    }

    // ---------- 账单 / 作业 ----------

    @PostMapping("/bills/download")
    public ResponseEntity<Object> downloadBill(@RequestParam String channelCode,
                                               @RequestParam(required = false) String bizDate) {
        try {
            return ResponseEntity.ok(billService.download(channelCode, bizDate));
        } catch (BizException e) {
            return ChannelOpsSupport.error(e);
        }
    }

    @PostMapping("/jobs/{name}")
    public ResponseEntity<Object> runJob(@PathVariable String name,
                                         @RequestParam(required = false) String param) {
        JobResult result = jobRegistry.invoke(name, 0, 1, param, "channel-ops");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/jobs")
    public ResponseEntity<Object> jobs() {
        return ResponseEntity.ok(jobRegistry.names());
    }

    /** 运行态开关观测（异步 opt-in / 路由策略 / 权重快照），验收脚本据此判定跑同步还是异步段。 */
    @GetMapping("/runtime")
    public ResponseEntity<Object> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("async", async);
        out.put("configProvider", config.provider());
        out.put("routeEnabled", routeRules.routeEnabled());
        out.put("failoverEnabled", routeRules.failoverEnabled());
        out.put("maxAttempts", routeRules.maxAttempts());
        out.put("defaultChannel", routeRules.defaultChannel());
        out.put("strategy", routeRules.strategyName());
        out.put("weights", String.valueOf(routeRules.weights()));
        out.put("adapters", registry.codes());
        out.put("billDir", config.get(com.qoder.payment.channel.config.ChannelConfigKeys.BILL_DIR,
                com.qoder.payment.channel.config.ChannelConfigKeys.DEFAULT_BILL_DIR));
        return ResponseEntity.ok(out);
    }

    /** 日累计按 biz_date 分行观测（跨日自然重置证据：昨日行不存在/为 0）。 */
    @GetMapping("/usage/{code}")
    public ResponseEntity<Object> usage(@PathVariable String code, @RequestParam(required = false) String bizDate) {
        String day = bizDate == null || bizDate.isBlank()
                ? ChannelLimitService.bizDate(System.currentTimeMillis()) : bizDate.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channelCode", code);
        out.put("bizDate", day);
        out.put("usedAmount", limitService.usedAmount(code, day));
        return ResponseEntity.ok(out);
    }

    // ---------- 内部 ----------

    private String baseUrl() {
        String url = config.get(com.qoder.payment.channel.config.ChannelConfigKeys.SANDBOX_BASE_URL,
                com.qoder.payment.channel.config.ChannelConfigKeys.DEFAULT_SANDBOX_BASE_URL);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ChannelTccContext ctx(Map<String, Object> body) {
        ChannelTccContext ctx = new ChannelTccContext();
        ctx.setBizNo(str(body, "bizNo"));
        ctx.setOrigBizNo(str(body, "origBizNo"));
        ctx.setCardNo(strOr(body, "cardNo", "6222020202020202"));
        ctx.setAmount(lngOr(body, "amount", 1000L));
        ctx.setChannelCode(str(body, "channelCode"));
        return ctx;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String strOr(Map<String, Object> body, String key, String def) {
        String v = str(body, key);
        return v == null || v.isBlank() ? def : v;
    }

    private static Long lng(Map<String, Object> body, String key) {
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
            throw new BizException(ResultCode.PARAM_ERROR, key + " 必须为整数（分）: " + v);
        }
    }

    private static long lngOr(Map<String, Object> body, String key, long def) {
        Long v = lng(body, key);
        return v == null ? def : v;
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}