package com.qoder.payment.gateway.web;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.notify.NotifyEvent;
import com.qoder.payment.facade.openplatform.OpenAppDTO;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import com.qoder.payment.facade.openplatform.SandboxOrderDTO;
import com.qoder.payment.facade.openplatform.SandboxTxnDTO;
import com.qoder.payment.gateway.config.StepUpTokenStore;
import com.qoder.payment.gateway.openplatform.SandboxRouter;
import com.qoder.payment.gateway.security.AuditSupport;
import com.qoder.payment.gateway.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * UI-B 平台治理观测面（<b>纯 additive</b>：既有端点与响应形状一字不改）。
 *
 * <p>补齐管理台「平台治理 / 开放平台 / 通知」三组页面此前缺失的<b>同源</b>只读端点：
 * <ul>
 *   <li><b>域运维面同源代理</b>：作业态 {@code /actuator/ppjob}、分片态 {@code /actuator/ppshard}
 *       与影子比对原本只在各域内网 HTTP 端口（Dubbo 端口 - 2800）上，浏览器跨域被拦。
 *       本控制器在网关侧服务端转发（服务名白名单 + 端口由 /api/health 推导 + 路径白名单，
 *       不接受任意 host/path，杜绝 SSRF），控制台从此拿到真实数据而非粘贴导入。</li>
 *   <li><b>Sentinel 生效规则</b>：直接读 FlowRuleManager / ParamFlowRuleManager / DegradeRuleManager
 *       的<b>当前生效</b>规则（不是部署基线副本），附规则文件 mtime/size 与热更新口径。</li>
 *   <li><b>沙箱姿态</b>：沙箱应用/账户/交易单/资金单 + 渠道故障注入实时态（回执丢失等）。</li>
 *   <li><b>文档与 SDK</b>：docs/*.md 与 sdk/** 的清单 + SHA-256（双 SDK 防漂移核对）与源码下载。</li>
 *   <li><b>通知模板</b>：以 {@link NotifyEvent} 契约为单一事实来源渲染模板骨架，
 *       并提供<b>只读校验</b>（不发送、不落库、不触发任何投递）。</li>
 * </ul>
 *
 * <p><b>权限</b>：GET 需登录（SecurityConfig 兜底 authenticated）；写操作（作业触发 / 分片切流 /
 * 故障注入）在控制器内强制 {@code console:admin} + {@code X-Step-Up-Token}（与既有敏感端点同口径，
 * 不改 SecurityConfig / StepUpAuthInterceptor 的既有匹配表）。
 *
 * <p><b>数据保护红线</b>：本控制器只做只读转发与显式写操作转发，绝不删改任何业务数据；
 * 分片切流仍由域侧「先全量比对 + 冲突检查，diff≠0 拒绝」护栏裁决。
 */
@Slf4j
@RestController
@RequestMapping("/api/platform")
@Tag(name = "平台治理观测 (UI-B)", description = "作业/分片/限流/沙箱/文档/SDK/通知模板的同源只读观测 + 敏感写转发")
public class PlatformOpsController {

    /** 域运维面允许转发的路径白名单（只读）。 */
    private static final List<String> READ_PATHS = List.of(
            "actuator/ppjob", "actuator/ppshard", "actuator/ppshard/shadowCompare",
            "actuator/ppshard/shardSummary", "actuator/ppshard/trial",
            "actuator/health", "actuator/info", "actuator/metrics", "actuator/prometheus",
            "channel-ops/faults");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HealthService healthService;
    private final StepUpTokenStore tokenStore;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500)).build();

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    public PlatformOpsController(HealthService healthService, StepUpTokenStore tokenStore, Environment env) {
        this.healthService = healthService;
        this.tokenStore = tokenStore;
        this.env = env;
    }

    /* ===================== 域清单（端口推导单一事实来源） ===================== */

    @GetMapping("/domains")
    @Operation(summary = "域运维面清单", description = "由 /api/health 的 Dubbo 端口推导内网 HTTP 端口（port-2800），供同源代理定位。")
    public ResponseEntity<Object> domains() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : healthService.list()) {
            int port = ((Number) row.get("port")).intValue();
            if (port < 20000) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("httpPort", port - 2800);
            item.put("base", "http://127.0.0.1:" + (port - 2800));
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    /* ===================== 调度作业 ===================== */

    @GetMapping("/jobs")
    @Operation(summary = "域作业态（同源代理 /actuator/ppjob）",
            description = "XXL 注册/探活、本地兜底调度、每作业触发与锁计数、ShedLock 锁表实时行。")
    public ResponseEntity<Object> jobs(@RequestParam String service) {
        return proxyGet(service, "actuator/ppjob");
    }

    @PostMapping("/jobs/{service}/{name}")
    @Operation(summary = "手动触发作业（敏感：console:admin + X-Step-Up-Token）",
            description = "转发 POST /actuator/ppjob/{name}，走与 XXL 相同注册表路径，ShedLock 与幂等护栏一致生效。")
    public ResponseEntity<Object> triggerJob(@PathVariable String service, @PathVariable String name,
                                             @RequestHeader(value = "X-Step-Up-Token", required = false) String stepToken,
                                             @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<Object> denied = guardWrite(stepToken);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shardIndex", intOf(body, "shardIndex", 0));
        payload.put("shardTotal", intOf(body, "shardTotal", 1));
        payload.put("param", strOf(body, "param", ""));
        log.info("控制台手动触发作业 service={} job={} operator={}", service, name, AuditSupport.currentUsername());
        return proxyPost(service, "actuator/ppjob/" + name, payload);
    }

    /* ===================== 分片与影子比对 ===================== */

    @GetMapping("/shard")
    @Operation(summary = "域分片态（同源代理 /actuator/ppshard）", description = "分片装配/切流态、双写镜像、物理行数、启动复制基线。")
    public ResponseEntity<Object> shard(@RequestParam String service) {
        return proxyGet(service, "actuator/ppshard");
    }

    @GetMapping("/shard/compare")
    @Operation(summary = "影子比对（只读，不改路由不动账）", description = "逐表 legacy vs shards 行数/缺失/值差异与样本，diffTotal 为切流前置门槛。")
    public ResponseEntity<Object> shardCompare(@RequestParam String service) {
        return proxyGet(service, "actuator/ppshard/shadowCompare");
    }

    @PostMapping("/shard/toggle")
    @Operation(summary = "分片切流/回滚（高危：console:admin + X-Step-Up-Token + confirm=SHARD）",
            description = "转发域侧 toggle；域侧先做全量影子比对 + 唯一键冲突检查，diff≠0 或 conflicts≠0 直接拒绝，不改动路由。")
    public ResponseEntity<Object> shardToggle(@RequestParam String service,
                                              @RequestHeader(value = "X-Step-Up-Token", required = false) String stepToken,
                                              @RequestBody Map<String, Object> body) {
        ResponseEntity<Object> denied = guardWrite(stepToken);
        if (denied != null) {
            return denied;
        }
        if (!"SHARD".equalsIgnoreCase(strOf(body, "confirm", ""))) {
            return ResponseEntity.status(400).body(GatewaySupport.errorBody(
                    "分片切流属高危操作，需 confirm=SHARD 确认词（且须用户明确拍板）", "A0400"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "toggle");
        payload.put("enabled", boolOf(body, "enabled", false));
        log.warn("控制台分片 toggle service={} enabled={} operator={}", service, payload.get("enabled"),
                AuditSupport.currentUsername());
        return proxyPost(service, "actuator/ppshard", payload);
    }

    /* ===================== Sentinel 生效规则 ===================== */

    @GetMapping("/sentinel")
    @Operation(summary = "Sentinel 当前生效规则 + 数据源状态",
            description = "直接读三个 RuleManager 的内存生效规则（非基线副本）；附规则文件路径/mtime/size 与热更新口径。")
    public ResponseEntity<Object> sentinel() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", env.getProperty("payment.sentinel.enabled", Boolean.class, true));
        String file = env.getProperty("payment.sentinel.rules.file",
                System.getProperty("user.home") + "/.config/payment-pro/sentinel-rules.json");
        Path path = Paths.get(file);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("file", file);
        source.put("exists", Files.exists(path));
        source.put("apolloEnabled", env.getProperty("payment.sentinel.apollo.enabled", Boolean.class, false));
        source.put("apolloNamespace", env.getProperty("payment.sentinel.apollo.namespace", "sentinel-rules"));
        source.put("pollSeconds", 5);
        try {
            if (Files.exists(path)) {
                source.put("size", Files.size(path));
                source.put("lastModified", Files.getLastModifiedTime(path).toMillis());
            }
        } catch (Exception e) {
            source.put("error", e.getClass().getSimpleName());
        }
        out.put("source", source);

        List<Map<String, Object>> flow = new ArrayList<>();
        for (FlowRule rule : FlowRuleManager.getRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("resource", rule.getResource());
            r.put("grade", rule.getGrade());
            r.put("count", rule.getCount());
            r.put("limitApp", rule.getLimitApp());
            r.put("strategy", rule.getStrategy());
            r.put("controlBehavior", rule.getControlBehavior());
            flow.add(r);
        }
        List<Map<String, Object>> param = new ArrayList<>();
        for (ParamFlowRule rule : ParamFlowRuleManager.getRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("resource", rule.getResource());
            r.put("paramIdx", rule.getParamIdx());
            r.put("grade", rule.getGrade());
            r.put("count", rule.getCount());
            r.put("durationInSec", rule.getDurationInSec());
            param.add(r);
        }
        List<Map<String, Object>> degrade = new ArrayList<>();
        for (DegradeRule rule : DegradeRuleManager.getRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("resource", rule.getResource());
            r.put("grade", rule.getGrade());
            r.put("count", rule.getCount());
            r.put("timeWindow", rule.getTimeWindow());
            r.put("minRequestAmount", rule.getMinRequestAmount());
            r.put("statIntervalMs", rule.getStatIntervalMs());
            r.put("slowRatioThreshold", rule.getSlowRatioThreshold());
            degrade.add(r);
        }
        out.put("flowRules", flow);
        out.put("paramFlowRules", param);
        out.put("degradeRules", degrade);
        out.put("note", "规则热更新：网关每 5s 轮询文件 mtime/size（docker 可切 Apollo）；"
                + "文件缺失/损坏 → 默认放行 + 内置保守熔断，不阻断支付。写操作在受理前拒绝，不落单。");
        return ResponseEntity.ok(out);
    }

    /* ===================== 沙箱姿态与故障注入 ===================== */

    @GetMapping("/sandbox")
    @Operation(summary = "沙箱姿态（应用/隔离/支持方法/故障注入）",
            description = "沙箱应用清单与生产应用对照、沙箱支持方法目录、渠道故障注入实时态；沙箱与生产资金账本物理隔离。")
    public ResponseEntity<Object> sandbox() {
        Map<String, Object> out = new LinkedHashMap<>();
        Result<List<OpenAppDTO>> apps = openPlatformFacade.listApps();
        List<OpenAppDTO> all = apps != null && apps.isSuccess() && apps.getData() != null
                ? apps.getData() : List.of();
        List<Map<String, Object>> sandboxApps = new ArrayList<>();
        int prod = 0;
        for (OpenAppDTO app : all) {
            if (Boolean.TRUE.equals(app.getSandbox())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appId", app.getAppId());
                row.put("name", app.getName());
                row.put("merchantId", app.getMerchantId());
                row.put("status", app.getStatus());
                row.put("scopes", app.getScopes());
                row.put("unitPrice", app.getUnitPrice());
                row.put("secretFingerprint", app.getSecretFingerprint());
                row.put("createdAt", app.getCreatedAt());
                sandboxApps.add(row);
            } else {
                prod++;
            }
        }
        out.put("apps", sandboxApps);
        out.put("sandboxAppCount", sandboxApps.size());
        out.put("prodAppCount", prod);
        out.put("supportedMethods", SandboxRouter.supportedMethods());
        out.put("isolation", Map.of(
                "accountPrefix", List.of("SBXA", "SBXM"),
                "orderPrefix", "SBXO",
                "txnPrefix", "SBXP",
                "tables", List.of("openapi_sandbox_account", "openapi_sandbox_order", "openapi_sandbox_txn"),
                "touchesFundLedger", false,
                "note", "沙箱请求在网关截流，只调 OpenPlatformFacade 沙箱方法，结构上不调用 trade/payment/accounting/account"));
        // 沙箱生产必关：生产 appId 不得进入沙箱路由；沙箱应用不得持有生产 scope 之外的资金能力
        out.put("prodGuard", Map.of(
                "sandboxRoutingByAppFlagOnly", true,
                "prodAppCanUseSandboxLedger", false,
                "sandboxAppCanUseProdLedger", false,
                "note", "沙箱截流仅按 appId 的 sandbox 标志判定；生产 appId 永远走生产链路，沙箱 appId 永远走沙箱账本"));
        out.put("faults", channelFaults());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/sandbox/{appId}/orders")
    @Operation(summary = "沙箱交易单列表（只读）", description = "按 appId 倒序返回沙箱交易单（SBXO），与生产交易单零交集。")
    public ResponseEntity<Object> sandboxOrders(@PathVariable String appId,
                                                @RequestParam(required = false) Integer limit) {
        return GatewaySupport.of(openPlatformFacade.sandboxListOrders(appId, limit));
    }

    @GetMapping("/sandbox/{appId}/txns")
    @Operation(summary = "沙箱资金单/支付单列表（只读）", description = "按 appId 倒序返回沙箱资金单（SBXP），fee=0、同步终态。")
    public ResponseEntity<Object> sandboxTxns(@PathVariable String appId,
                                              @RequestParam(required = false) Integer limit) {
        return GatewaySupport.of(openPlatformFacade.sandboxListTxns(appId, limit));
    }

    @GetMapping("/sandbox/orders/{appId}/{orderId}")
    @Operation(summary = "沙箱交易单详情（含支付单）", description = "沙箱单据联查：order + payment（SBXO ↔ SBXP）。")
    public ResponseEntity<Object> sandboxOrder(@PathVariable String appId, @PathVariable String orderId) {
        Result<SandboxOrderDTO> order = openPlatformFacade.sandboxGetOrder(appId, orderId);
        if (order == null || !order.isSuccess() || order.getData() == null) {
            return GatewaySupport.of(order);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order.getData());
        if (order.getData().getPaymentId() != null) {
            Result<SandboxTxnDTO> txn = openPlatformFacade.sandboxGetTxn(appId, order.getData().getPaymentId());
            if (txn != null && txn.isSuccess()) {
                out.put("payment", txn.getData());
            }
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/faults")
    @Operation(summary = "渠道沙箱故障注入实时态（只读）", description = "延迟/拒绝/回执丢失/金额不符/宕机/超时 + 账单差异注入项。")
    public ResponseEntity<Object> faults() {
        Object faults = channelFaults();
        if (faults instanceof Map<?, ?> map && map.containsKey("error")) {
            return ResponseEntity.status(503).body(faults);
        }
        return ResponseEntity.ok(faults);
    }

    @PostMapping("/faults")
    @Operation(summary = "设置沙箱故障注入（敏感：console:admin + X-Step-Up-Token）",
            description = "只作用于渠道进程内模拟银行与其账单文件，不连接真实银行、不改写任何既有业务数据。")
    public ResponseEntity<Object> setFaults(@RequestHeader(value = "X-Step-Up-Token", required = false) String stepToken,
                                            @RequestBody Map<String, Object> body) {
        ResponseEntity<Object> denied = guardWrite(stepToken);
        if (denied != null) {
            return denied;
        }
        log.info("控制台设置沙箱故障注入 keys={} operator={}", body.keySet(), AuditSupport.currentUsername());
        return proxyPost("channel", "channel-ops/faults", body);
    }

    @PostMapping("/faults/reset")
    @Operation(summary = "复位沙箱故障注入（敏感：console:admin + X-Step-Up-Token）", description = "全部注入项恢复出厂（进程内内存态）。")
    public ResponseEntity<Object> resetFaults(@RequestHeader(value = "X-Step-Up-Token", required = false) String stepToken) {
        ResponseEntity<Object> denied = guardWrite(stepToken);
        if (denied != null) {
            return denied;
        }
        log.info("控制台复位沙箱故障注入 operator={}", AuditSupport.currentUsername());
        return proxyPost("channel", "channel-ops/faults/reset", Map.of());
    }

    /* ===================== 文档与 SDK ===================== */

    @GetMapping("/docs")
    @Operation(summary = "契约文档清单", description = "docs/*.md 清单（名称/字节数/SHA-256/标题），供控制台内嵌渲染。")
    public ResponseEntity<Object> docs() {
        Path root = repoRoot();
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = root.resolve("docs");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", p.getFileName().toString());
                    row.put("title", firstHeading(p));
                    row.put("bytes", sizeOf(p));
                    row.put("sha256", sha256(p));
                    out.add(row);
                });
            } catch (Exception e) {
                log.warn("文档清单读取失败: {}", e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", dir.toString());
        result.put("docs", out);
        result.put("springdoc", List.of("/v3/api-docs", "/swagger-ui.html"));
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/docs/{name}", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "契约文档原文（markdown）", description = "只允许 docs/ 下的 .md 文件名，禁止路径穿越。")
    public ResponseEntity<String> doc(@PathVariable String name) {
        if (!name.endsWith(".md") || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return ResponseEntity.badRequest().body("非法文档名");
        }
        Path file = repoRoot().resolve("docs").resolve(name);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(404).body("文档不存在: " + name);
        }
        try {
            return ResponseEntity.ok(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("文档读取失败: " + e.getClass().getSimpleName());
        }
    }

    @GetMapping("/sdk")
    @Operation(summary = "双 SDK 清单与防漂移校验值",
            description = "python/java SDK 源文件的字节数与 SHA-256（双端逐字节核对基线）+ 契约探针入口说明。")
    public ResponseEntity<Object> sdk() {
        Path root = repoRoot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root.resolve("sdk").toString());
        out.put("python", sdkFiles(root, "sdk/python", ".py"));
        out.put("java", sdkFiles(root, "sdk/java/src", ".java"));
        out.put("buildScript", Files.isRegularFile(root.resolve("sdk/java/build.sh")));
        out.put("contractProbe", "java -cp sdk/java/out com.qoder.payment.sdk.ContractProbe");
        out.put("driftGuard", "双 SDK 与服务端 canonical 签名逐字节一致：TreeMap 排序 k=v&（剔除 sign、跳过 null）"
                + "+ HMAC-SHA256 小写 hex；毫秒 timestamp ±300s；nonce TTL 300s；布尔小写 true/false。"
                + "ContractProbe 无网络自检方法目录与归一逐例；SHA-256 可用于跨环境防漂移核对。");
        return ResponseEntity.ok(out);
    }

    @GetMapping(value = "/sdk/file", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "SDK 源码下载（白名单 sdk/ 目录）", description = "path 必须是 sdk/ 下相对路径，禁止路径穿越；返回源码原文。")
    public ResponseEntity<String> sdkFile(@RequestParam String path) {
        if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            return ResponseEntity.badRequest().body("非法路径");
        }
        Path root = repoRoot().resolve("sdk").toAbsolutePath().normalize();
        Path file = root.resolve(path).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.status(404).body("文件不存在: " + path);
        }
        try {
            return ResponseEntity.ok(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("读取失败: " + e.getClass().getSimpleName());
        }
    }

    /* ===================== 通知模板（契约单一事实来源 + 只读校验） ===================== */

    @GetMapping("/notify/templates")
    @Operation(summary = "通知事件模板目录（契约渲染）",
            description = "以 NotifyEvent 枚举为单一事实来源：冻结/信封标志、必填字段、body 骨架与签名口径。")
    public ResponseEntity<Object> notifyTemplates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NotifyEvent event : NotifyEvent.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event", event.name());
            row.put("description", event.getDescription());
            row.put("legacy", event.isLegacy());
            row.put("envelope", !event.isLegacy());
            row.put("requiredFields", event.getRequiredFields());
            row.put("idempotentKey", "event:bizId");
            row.put("bodyTemplate", templateBody(event));
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", out);
        result.put("signSpec", Map.of(
                "algorithm", "HMAC_SHA256",
                "canonical", "TreeMap 排序 k=v&（剔除 sign、跳过 null）",
                "timestamp", "毫秒，±300s 窗口",
                "nonce", "可选，TTL 300s 防重放",
                "rsa2", "SHA256withRSA + Base64（sign_type=RSA2）"));
        result.put("note", "模板即事件契约（服务端强校验），无可编辑模板表；"
                + "本页可编辑 payload 并做服务端契约校验（POST /api/platform/notify/templates/validate，只校验不发送）。");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/notify/templates/validate")
    @Operation(summary = "通知模板契约校验（只读，不发送）",
            description = "按 NotifyEvent.requiredFields 校验 payload，返回将要投递的 body 骨架；"
                    + "不落库、不发送、不触发任何回调。")
    public ResponseEntity<Object> validateTemplate(@RequestBody Map<String, Object> body) {
        String eventName = strOf(body, "event", "");
        NotifyEvent event = NotifyEvent.of(eventName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", eventName);
        out.put("registered", event != null);
        Object payloadValue = body.get("payload");
        Map<?, ?> payload = payloadValue instanceof Map<?, ?> map ? map : Map.of();
        List<String> missing = new ArrayList<>();
        if (event != null && !event.isLegacy()) {
            for (String field : event.getRequiredFields()) {
                Object value = payload.get(field);
                if (value == null || String.valueOf(value).isBlank()) {
                    missing.add(field);
                }
            }
        }
        out.put("legacy", event == null || event.isLegacy());
        out.put("requiredFields", event == null ? List.of() : event.getRequiredFields());
        out.put("missingFields", missing);
        out.put("valid", missing.isEmpty());
        out.put("bizId", strOf(body, "bizId", ""));
        out.put("renderedBody", renderBody(event, strOf(body, "bizId", "<业务号>"), payload));
        out.put("note", event == null
                ? "未登记事件按冻结事件原样透传（兼容红线：既有调用方零影响）"
                : event.isLegacy() ? "冻结事件：body = payload 原样，逐字节不变" : "新事件：统一 envelope + 必填字段强校验");
        return ResponseEntity.ok(out);
    }

    /* ===================== 内部工具 ===================== */

    /** 写操作闸门：console:admin（或通配）+ 有效 step-up token；与既有敏感端点同错误口径。 */
    private ResponseEntity<Object> guardWrite(String stepToken) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "*".equals(a) || "console:admin".equals(a));
        if (!admin) {
            return ResponseEntity.status(403).body(GatewaySupport.errorBody("无权限执行该操作", "A0403"));
        }
        if (tokenStore.validate(stepToken) == null) {
            return ResponseEntity.status(403).body(GatewaySupport.errorBody(
                    "敏感操作需二次认证：X-Step-Up-Token 缺失或失效（POST /api/security/step-up 获取）", "A0403"));
        }
        return null;
    }

    /** 服务名 → 内网基址（白名单来自 /api/health，端口推导，绝不接受外部 host）。 */
    private String baseOf(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        for (Map<String, Object> row : healthService.list()) {
            if (!service.equals(row.get("service"))) {
                continue;
            }
            int port = ((Number) row.get("port")).intValue();
            return port >= 20000 ? "http://127.0.0.1:" + (port - 2800) : null;
        }
        return null;
    }

    private ResponseEntity<Object> proxyGet(String service, String path) {
        if (!READ_PATHS.contains(path)) {
            return ResponseEntity.status(400).body(GatewaySupport.errorBody("路径不在白名单: " + path, "A0400"));
        }
        String base = baseOf(service);
        if (base == null) {
            return ResponseEntity.status(404).body(GatewaySupport.errorBody("未知服务或无运维端口: " + service, "A0404"));
        }
        return forward(base + "/" + path, "GET", null);
    }

    private ResponseEntity<Object> proxyPost(String service, String path, Map<String, Object> body) {
        String base = baseOf(service);
        if (base == null) {
            return ResponseEntity.status(404).body(GatewaySupport.errorBody("未知服务或无运维端口: " + service, "A0404"));
        }
        return forward(base + "/" + path, "POST", body);
    }

    /** 服务端转发：JSON 原样透传（形状不改），非 JSON（prometheus）以 {raw:...} 返回。 */
    private ResponseEntity<Object> forward(String url, String method, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body == null ? Map.of() : body),
                                StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            Object parsed;
            try {
                parsed = JSON.readValue(text, Object.class);
            } catch (Exception e) {
                parsed = Map.of("raw", text == null ? "" : text);
            }
            if (response.statusCode() >= 400) {
                String message = parsed instanceof Map<?, ?> map && map.get("error") != null
                        ? String.valueOf(map.get("error")) : "域运维面返回 " + response.statusCode();
                return ResponseEntity.status(response.statusCode() == 404 ? 404 : 502)
                        .body(GatewaySupport.errorBody(message, response.statusCode() == 404 ? "A0404" : "C0001"));
            }
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            log.warn("域运维面转发失败 url={}: {}", url, e.toString());
            return ResponseEntity.status(503).body(GatewaySupport.errorBody(
                    "域运维面不可达（" + e.getClass().getSimpleName() + "）：" + url, "C0001"));
        }
    }

    private Object channelFaults() {
        String base = baseOf("channel");
        if (base == null) {
            return Map.of("error", "渠道域未就绪");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(base + "/channel-ops/faults"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of("error", "渠道故障注入态返回 " + response.statusCode());
            }
            return JSON.readValue(response.body(), Object.class);
        } catch (Exception e) {
            return Map.of("error", "渠道故障注入态不可达：" + e.getClass().getSimpleName());
        }
    }

    /** 仓库根：run.sh 以仓库根为 CWD 启动；兼容 IDE/子目录启动时向上找 pom.xml + docs。 */
    private Path repoRoot() {
        String configured = env.getProperty("payment.platform.repo-root");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && current != null; i++) {
            if (Files.isDirectory(current.resolve("docs")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private List<Map<String, Object>> sdkFiles(Path root, String relative, String suffix) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = root.resolve(relative);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .forEach(p -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", root.relativize(p).toString().replace('\\', '/'));
                        row.put("name", p.getFileName().toString());
                        row.put("bytes", sizeOf(p));
                        row.put("sha256", sha256(p));
                        out.add(row);
                    });
        } catch (Exception e) {
            log.warn("SDK 清单读取失败 dir={}: {}", dir, e.getMessage());
        }
        return out;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String sha256(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstHeading(Path path) {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.startsWith("# ")) {
                    return line.substring(2).trim();
                }
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    /** 事件 body 骨架（与 NotifyService.buildBody 口径一致：冻结原样 / 新事件 envelope）。 */
    private static Map<String, Object> templateBody(NotifyEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : event.getRequiredFields()) {
            payload.put(field, "<" + field + ">");
        }
        return renderBody(event, "<业务号>", payload);
    }

    private static Map<String, Object> renderBody(NotifyEvent event, String bizId, Map<?, ?> payload) {
        Map<String, Object> clean = new LinkedHashMap<>();
        payload.forEach((k, v) -> clean.put(String.valueOf(k), v));
        if (event == null || event.isLegacy()) {
            return clean;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", event.name());
        envelope.put("bizId", bizId);
        envelope.put("timestamp", "<毫秒时间戳>");
        envelope.put("payload", clean);
        return envelope;
    }

    private static String strOf(Map<String, Object> body, String key, String defaultValue) {
        Object value = body == null ? null : body.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private static int intOf(Map<String, Object> body, String key, int defaultValue) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean boolOf(Map<String, Object> body, String key, boolean defaultValue) {
        Object value = body == null ? null : body.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }
}