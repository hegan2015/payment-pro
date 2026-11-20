package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.facade.openplatform.OpenApiScopes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-4b OpenAPI 版本协商与弃用公告。
 *
 * <p><b>兼容红线</b>：{@code /openapi/<method>} 永远是 v1，<b>请求头不可能把 v1 端点变成 v2 行为</b>；
 * v2 只能通过显式路径 {@code /openapi/v2/<method>} 进入（additive 新端点）。
 * 缺省版本 v1 —— ride-pro 不传任何版本头，行为逐字节不变。
 *
 * <p>协商结果只以<b>新增响应头</b>回显（X-Api-Version / X-Api-Version-Requested / X-Api-Deprecated /
 * X-Api-Sunset / X-Api-Sandbox），响应体结构一字不改。
 */
@Slf4j
@Component
public class ApiVersionRouter {

    public static final String V1 = "v1";
    public static final String V2 = "v2";
    public static final String HEADER = "X-Api-Version";

    /**
     * C-5 多端收银台 9 方法 + CH-6 签约代扣 2 方法（<b>additive 新端点，v1 路径承载</b>）。
     * v1 冻结面（13 个既有方法的入参/返回/幂等键/语义）一字不改；未注册 appId 仍走 legacy 全放行。
     */
    public static final List<String> ADDITIVE_METHODS = List.of(
            "cashier.methods", "cashier.code.create", "cashier.code.parse", "cashier.code.query",
            "cashier.scanPay", "cashier.verify", "cashier.payVerified", "cashier.payPassword.set",
            "cashier.sms.send", "channel.sign", "channel.agent.pay");

    /** 弃用版本清单（可配，默认空 = v1 仍是 STABLE，不发弃用公告）。 */
    private final List<String> deprecated;
    private final String sunset;

    public ApiVersionRouter(@Value("${payment.openapi.deprecated-versions:}") String deprecatedVersions,
                            @Value("${payment.openapi.sunset:}") String sunset) {
        this.deprecated = new ArrayList<>();
        for (String version : deprecatedVersions.split(",")) {
            if (!version.isBlank()) {
                this.deprecated.add(version.trim().toLowerCase());
            }
        }
        this.sunset = sunset == null || sunset.isBlank() ? null : sunset.trim();
    }

    /** 路径优先：/openapi/v2/xxx → v2；其余一律 v1（头只做期望值回显）。 */
    public String effectiveVersion(String requestUri) {
        return requestUri != null && requestUri.startsWith("/openapi/v2/") ? V2 : V1;
    }

    /** 去掉版本前缀得到方法名（scope/计费用方法名，v1/v2 同一套授权与计量口径）。 */
    public static String methodOf(String requestUri) {
        String path = requestUri.substring("/openapi/".length());
        return path.startsWith("v2/") ? path.substring(3) : path;
    }

    public boolean isDeprecated(String version) {
        return deprecated.contains(version);
    }

    public String sunset() {
        return sunset;
    }

    /** 版本公告（GET /openapi/version，免签名免 Bearer，供 SDK 协商）。 */
    public Map<String, Object> announcement(String appDefaultVersion) {
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("version", V1);
        v1.put("status", isDeprecated(V1) ? "DEPRECATED" : "STABLE");
        v1.put("basePath", "/openapi/");
        v1.put("frozen", true);
        v1.put("deprecated", isDeprecated(V1));
        v1.put("sunset", isDeprecated(V1) ? sunset : null);
        v1.put("methods", List.of("account.open", "fund.topup", "fund.withdraw", "account.transfer",
                "account.balance", "trade.create", "trade.pay", "trade.query",
                "account.topup", "account.withdraw", "trade.refund", "notify.query", "notify.resend"));
        v1.put("compatibility", "入参名/返回字段/幂等键/语义冻结；account.balance 裸数字 long；HMAC 免 Bearer");
        // C-5/CH-6：新方法 additive 登记（不改上面 methods 冻结清单，弃用公告只针对版本不针对方法）
        v1.put("additiveMethods", ADDITIVE_METHODS);
        v1.put("additiveNote", "cashier.* 9 方法（多端收银台对接能力）+ channel.sign/channel.agent.pay（签约代扣）"
                + "均为 additive 新端点，v1 冻结面不变；未注册 appId（如 ride-pro appM1）走 legacy 不限流不限配额单价 0");

        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("version", V2);
        v2.put("status", "CURRENT");
        v2.put("basePath", "/openapi/v2/");
        v2.put("frozen", false);
        v2.put("deprecated", false);
        v2.put("methods", List.of("trade.create", "trade.query", "account.balance"));
        v2.put("additive", List.of("响应 envelope {apiVersion,data}", "trade.create 可选 attach 回显",
                "account.balance 返回 {accountId,balance,currency}", "trade.query 增 fee 明细"));

        Map<String, Object> negotiation = new LinkedHashMap<>();
        negotiation.put("header", HEADER);
        negotiation.put("pathPrefix", "/openapi/v2/");
        negotiation.put("default", V1);
        negotiation.put("rule", "路径优先；/openapi/<method> 恒为 v1，头不可改变 v1 行为");
        negotiation.put("appDefault", appDefaultVersion == null ? V1 : appDefaultVersion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("default", V1);
        result.put("current", V2);
        result.put("versions", List.of(v1, v2));
        result.put("negotiation", negotiation);
        result.put("sign", Map.of("algorithm", "HMAC-SHA256", "canonical", "TreeMap 排序 k=v&（剔除 sign、跳过 null）",
                "timestamp", "毫秒，±300s", "nonce", "可选，TTL 300s", "signType", "HMAC(默认)/RSA2"));
        result.put("scopes", OpenApiScopes.catalog());
        result.put("multiclient", multiclient());
        result.put("sandbox", sandbox());
        return result;
    }

    /** C-5 多端对接能力公告（业务方自渲染收银台；payment-pro 不自建 C 端页面）。 */
    private static Map<String, Object> multiclient() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capability", "多端对接能力：开放 OpenAPI + 双 SDK + 契约文档，业务方（ride-pro 及未来其他端）自渲染收银台");
        map.put("clients", List.of("H5", "MINI_APP", "APP", "PC"));
        map.put("cashierMethods", ADDITIVE_METHODS.subList(0, 9));
        map.put("signMethods", ADDITIVE_METHODS.subList(9, 11));
        map.put("qrNormalization", "主扫 qrContent 是扫码 URL https://cashier.payment-pro.example/scan?code=<token>&amount=<amt>；"
                + "接入方须从 code= 归一取 token 再调 code.parse/scanPay（SDK normalize_scan_code / normalizeScanCode）；"
                + "被扫 qrContent 等于码值本身");
        map.put("verifyTypes", List.of("PASSWORD", "SMS", "BIO", "SMALL_AMOUNT_FREE"));
        map.put("requestId", "全局幂等键（SDK new_request_id / newRequestId 生成）；重试必须原样复用同键，"
                + "同键重放返回同一单且 repeated=true，不重复扣款");
        map.put("polling", "只读回查至终态：trade.query（支付）/ cashier.code.query（码 ACTIVE→CONSUMED/EXPIRED）/ "
                + "channel.agent.pay 同 requestId 重放（扣款单）");
        map.put("sdk", Map.of("python", "sdk/python/payment_openapi.py",
                "java", "sdk/java/src/com/qoder/payment/sdk/PaymentOpenApiClient.java"));
        map.put("docs", List.of("docs/cashier-openapi.md", "docs/cashier-multiclient.md"));
        return map;
    }

    /** G-4a 沙箱能力公告（含 C-5/CH-6 新方法登记；账本隔离，生产零污染）。 */
    private static Map<String, Object> sandbox() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("isolation", "沙箱 appId 请求在网关截流，只走 member 域 openapi_sandbox_account/_txn/_order 账本"
                + "（id 前缀 SBXA/SBXM/SBXO/SBXP），生产账户/支付单/账务一行不动");
        map.put("methods", SandboxRouter.supportedMethods());
        map.put("cashierMethods", SandboxCashierSupport.METHODS.subList(0, 9));
        map.put("signMethods", SandboxCashierSupport.METHODS.subList(9, 11));
        map.put("semantics", "fee=0、同步 SUCCESS、仅 BALANCE、bizNo/outTradeNo/requestId 幂等；"
                + "付款码/口令哈希/短信码/验证会话/签约协议/扣款单为按 appId 隔离的进程内沙箱态（联调用，重启即清）");
        map.put("responseHeader", "X-Api-Sandbox: true");
        map.put("registration", "POST /api/openplatform/apps {sandbox:true} → 一次性返回 appId/appSecret");
        return map;
    }
}