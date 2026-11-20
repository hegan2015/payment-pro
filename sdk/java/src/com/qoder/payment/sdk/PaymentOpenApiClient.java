package com.qoder.payment.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * G-4b Java SDK 客户端（JDK 11+ 内置 HttpClient，零第三方依赖）。
 *
 * <p>能力：HMAC/RSA2 签名、毫秒 timestamp、可选 nonce、版本路由（v1 冻结 / v2 additive envelope 自动解包）、
 * 幂等重试（网络/5xx/B0003 重试时 bizNo/outTradeNo/requestId 原样不变）、错误码映射、OAuth token 授权（与签名并存）。
 *
 * <p><b>C-5 多端对接能力</b>：另封装 9 个收银台方法（{@code cashier.*}）与 2 个签约代扣方法
 * （{@code channel.sign}/{@code channel.agent.pay}），供业务方（ride-pro 及未来 H5/小程序/App/PC 各端）
 * <b>自行渲染收银台</b>——payment-pro 不自建 C 端页面。含主扫 {@code qrContent} 的 {@code code=} 归一工具
 * {@link #normalizeScanCode(String)}、幂等键生成 {@link #newRequestId(String)} 与轮询终态助手。
 * 多端适配指南：{@code docs/cashier-multiclient.md}；契约：{@code docs/cashier-openapi.md}。
 */
public class PaymentOpenApiClient {

    private static final Set<String> V2_METHODS = Set.of("trade.create", "trade.query", "account.balance");

    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final String apiVersion;
    private final int retries;
    private final boolean useNonce;
    private final HttpClient http;
    private String accessToken;
    private Map<String, String> lastHeaders = Map.of();

    public PaymentOpenApiClient(String baseUrl, String appId, String appSecret) {
        this(baseUrl, appId, appSecret, "v1", 2, false);
    }

    public PaymentOpenApiClient(String baseUrl, String appId, String appSecret, String apiVersion,
                                int retries, boolean useNonce) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.appId = appId;
        this.appSecret = appSecret;
        this.apiVersion = apiVersion == null ? "v1" : apiVersion;
        this.retries = retries;
        this.useNonce = useNonce;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, String> lastHeaders() {
        return lastHeaders;
    }

    // ---------- 授权 / 版本 ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> token(String scope) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("appSecret", appSecret);
        if (scope != null) {
            body.put("scope", scope);
        }
        Response response = send("POST", "/openapi/oauth/token", body, Map.of());
        if (response.status != 200) {
            throw new OpenApiException(String.valueOf(response.body.get("code")),
                    String.valueOf(response.body.get("error")), response.status);
        }
        accessToken = String.valueOf(response.body.get("access_token"));
        return response.body;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> version() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/openapi/version?appId=" + appId))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(response.body());
    }

    // ---------- 冻结 v1 八方法 ----------

    public Map<String, Object> accountOpen(String name, String type) throws Exception {
        return asMap(call("account.open", Map.of("name", name, "type", type)));
    }

    public long accountBalance(String accountId) throws Exception {
        Object value = call("account.balance", Map.of("accountId", accountId));
        if (value instanceof Map<?, ?> map) { // v2 envelope 解包后的对象
            return ((Number) map.get("balance")).longValue();
        }
        return ((Number) value).longValue();
    }

    public Map<String, Object> fundTopup(String accountId, long amount, String bizNo) throws Exception {
        return asMap(call("fund.topup", Map.of("accountId", accountId, "amount", amount, "bizNo", bizNo)));
    }

    public Map<String, Object> fundWithdraw(String accountId, long amount, String bizNo) throws Exception {
        return asMap(call("fund.withdraw", Map.of("accountId", accountId, "amount", amount, "bizNo", bizNo)));
    }

    public void accountTransfer(String from, String to, long amount, String bizNo) throws Exception {
        call("account.transfer", Map.of("fromAccountId", from, "toAccountId", to, "amount", amount, "bizNo", bizNo));
    }

    public Map<String, Object> tradeCreate(String subject, long amount, String outTradeNo) throws Exception {
        return asMap(call("trade.create", Map.of("subject", subject, "amount", amount, "outTradeNo", outTradeNo)));
    }

    public Map<String, Object> tradePay(String orderId, String accountId, String method) throws Exception {
        return asMap(call("trade.pay", Map.of("orderId", orderId, "accountId", accountId, "method", method)));
    }

    public Map<String, Object> tradeQuery(String orderId) throws Exception {
        return asMap(call("trade.query", Map.of("orderId", orderId)));
    }

    // ---------- C-5 收银台 9 方法（多端对接能力：业务方自渲染收银台） ----------

    /** 支付方式渲染：CashierDTO{order,methods[],actualAmount,freeVerifyLimit,currency}。 */
    public Map<String, Object> cashierMethods(String orderId, String accountId) throws Exception {
        return asMap(call("cashier.methods", params("orderId", orderId, "accountId", accountId)));
    }

    /** 生成付款码/收款码：type=USER_PRESENTED(被扫)/MERCHANT_PRESENTED(主扫，qrContent 是扫码 URL)。 */
    public Map<String, Object> cashierCodeCreate(String type, String accountId, String orderId,
                                                 Long amount, String requestId) throws Exception {
        return asMap(call("cashier.code.create", params("type", type, "accountId", accountId,
                "orderId", orderId, "amount", amount, "requestId", requestId)));
    }

    /** 解析码（只读，不核销）；入参可为 qrContent，内部自动做主扫 code= 归一。 */
    public Map<String, Object> cashierCodeParse(String code) throws Exception {
        return asMap(call("cashier.code.parse", params("code", normalizeScanCode(code))));
    }

    /** 查询码状态（轮询 ACTIVE→CONSUMED/EXPIRED）。 */
    public Map<String, Object> cashierCodeQuery(String code) throws Exception {
        return asMap(call("cashier.code.query", params("code", normalizeScanCode(code))));
    }

    /** 扫码受理（一次性核销 + 验证前置 + 幂等支付）；主扫必传 payerAccountId。 */
    public Map<String, Object> cashierScanPay(String code, String requestId, String payerAccountId,
                                              String method, String verifyToken, String payPassword,
                                              String cardNo, String callbackUrl) throws Exception {
        return asMap(call("cashier.scanPay", params("code", normalizeScanCode(code), "requestId", requestId,
                "payerAccountId", payerAccountId, "method", method, "verifyToken", verifyToken,
                "payPassword", payPassword, "cardNo", cardNo, "callbackUrl", callbackUrl)));
    }

    /** 支付验证四型：PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE → 一次性 verifyToken。 */
    public Map<String, Object> cashierVerify(String accountId, String memberId, String orderId, Long amount,
                                             String verifyType, String payPassword, String smsCode,
                                             String bioToken, String requestId) throws Exception {
        return asMap(call("cashier.verify", params("accountId", accountId, "memberId", memberId,
                "orderId", orderId, "amount", amount, "verifyType", verifyType, "payPassword", payPassword,
                "smsCode", smsCode, "bioToken", bioToken, "requestId", requestId)));
    }

    /** 验证前置受理（幂等）：amount ≤ freeVerifyLimit 可免验证；BALANCE 同步终态。 */
    public Map<String, Object> cashierPayVerified(String orderId, String requestId, String method,
                                                  String accountId, String verifyToken, String payPassword,
                                                  String cardNo, String callbackUrl) throws Exception {
        return asMap(call("cashier.payVerified", params("orderId", orderId, "requestId", requestId,
                "method", method, "accountId", accountId, "verifyToken", verifyToken,
                "payPassword", payPassword, "cardNo", cardNo, "callbackUrl", callbackUrl)));
    }

    /** 设置/重置支付口令（6 位数字）；返回裸 boolean，口令只入不出。 */
    public boolean cashierPayPasswordSet(String password, String accountId, String memberId,
                                         String oldPassword) throws Exception {
        Object value = call("cashier.payPassword.set", params("password", password, "accountId", accountId,
                "memberId", memberId, "oldPassword", oldPassword));
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    /** 发送短信验证码：默认掩码 ******；仅 sandbox=true 联调返回明文。 */
    public Map<String, Object> cashierSmsSend(String accountId, String memberId, String orderId,
                                              Boolean sandbox) throws Exception {
        return asMap(call("cashier.sms.send", params("accountId", accountId, "memberId", memberId,
                "orderId", orderId, "sandbox", sandbox)));
    }

    // ---------- CH-6 签约代扣 2 方法（OpenAPI 开放面） ----------

    /** 签约（action 缺省 SIGN）：幂等键 requestId + (accountId,merchantId,channelCode) 三元组。 */
    public Map<String, Object> channelSign(String requestId, String accountId, String merchantId,
                                           String cardNo) throws Exception {
        return channelSign(requestId, accountId, merchantId, cardNo, Map.of());
    }

    /**
     * 签约（可选额度/有效期/扣款日/重试策略）。options 支持：channelCode/singleLimit/periodLimit/
     * periodType/effectiveAt/expiredAt/deductDay/maxRetry/retryIntervalMs/callbackUrl/scopeNote。
     * 返回 SignContractDTO（卡号只留掩码 + SHA-256 哈希，绝不含明文）。
     */
    public Map<String, Object> channelSign(String requestId, String accountId, String merchantId,
                                           String cardNo, Map<String, Object> options) throws Exception {
        Map<String, Object> body = params("requestId", requestId, "accountId", accountId,
                "merchantId", merchantId, "cardNo", cardNo, "action", "SIGN");
        body.putAll(options);
        return asMap(call("channel.sign", body));
    }

    /** 解约（action=UNSIGN）：SIGNED→CANCELLED CAS，幂等；解约后不可代扣。 */
    public Map<String, Object> channelUnsign(String signNo, String requestId, String reason) throws Exception {
        return asMap(call("channel.sign", params("action", "UNSIGN", "signNo", signNo,
                "requestId", requestId, "reason", reason)));
    }

    /** 代扣执行（幂等 requestId）：SignDeductDTO{deductNo,status,paymentId,amount,repeated}。 */
    public Map<String, Object> channelAgentPay(String signNo, long amount, String requestId,
                                               String merchantId, String subject, String callbackUrl)
            throws Exception {
        return asMap(call("channel.agent.pay", params("signNo", signNo, "amount", amount,
                "requestId", requestId, "merchantId", merchantId, "subject", subject,
                "callbackUrl", callbackUrl)));
    }

    // ---------- 多端工具：主扫归一 / 幂等键 / 轮询终态 ----------

    /**
     * C-5 主扫二维码归一（<b>各端必须调用</b>）：主扫 qrContent 是扫码 URL
     * {@code https://cashier.payment-pro.example/scan?code=<token>&amount=<amt>}，
     * 须从 {@code code=} 查询参数取 token 再调 code.parse/scanPay；被扫码值原样返回。
     */
    public static String normalizeScanCode(String qrContent) {
        if (qrContent == null) {
            return null;
        }
        String text = qrContent.trim();
        if (text.isEmpty()) {
            return text;
        }
        int mark = text.indexOf('?');
        String query = mark >= 0 ? text.substring(mark + 1) : (text.contains("code=") ? text : null);
        if (query != null) {
            int hash = query.indexOf('#');
            if (hash >= 0) {
                query = query.substring(0, hash);
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "code".equals(pair.substring(0, eq))) {
                    String value = urlDecode(pair.substring(eq + 1));
                    if (!value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return text;
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /** 幂等 requestId 生成建议：业务前缀 + uuid（≤64 字符）；<b>重试必须原样复用</b>。 */
    public static String newRequestId(String prefix) {
        String value = (prefix == null || prefix.isBlank() ? "PAY" : prefix) + "-"
                + UUID.randomUUID().toString().replace("-", "");
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /** 轮询 trade.query 至支付终态（BALANCE 同步，通常首次即 SUCCESS）。 */
    public Map<String, Object> waitOrderTerminal(String orderId, long timeoutMs, long intervalMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> value = tradeQuery(orderId);
        while (System.currentTimeMillis() < deadline && !paymentTerminal(value)) {
            Thread.sleep(intervalMs);
            value = tradeQuery(orderId);
        }
        return value;
    }

    /** 被扫端轮询付款码 ACTIVE→CONSUMED/EXPIRED。 */
    public Map<String, Object> waitCodeConsumed(String code, long timeoutMs, long intervalMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> value = cashierCodeQuery(code);
        while (System.currentTimeMillis() < deadline
                && !Set.of("CONSUMED", "EXPIRED").contains(String.valueOf(value.get("status")))) {
            Thread.sleep(intervalMs);
            value = cashierCodeQuery(code);
        }
        return value;
    }

    /** 代扣轮询：同 requestId 幂等重放即查询（不新建扣款单），至 SUCCESS/FAILED/TERMINATED。 */
    public Map<String, Object> waitDeductTerminal(String signNo, long amount, String requestId,
                                                  long timeoutMs, long intervalMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> value = channelAgentPay(signNo, amount, requestId, null, null, null);
        while (System.currentTimeMillis() < deadline
                && !Set.of("SUCCESS", "FAILED", "TERMINATED").contains(String.valueOf(value.get("status")))) {
            Thread.sleep(intervalMs);
            value = channelAgentPay(signNo, amount, requestId, null, null, null);
        }
        return value;
    }

    private static boolean paymentTerminal(Map<String, Object> queryResult) {
        Object payment = queryResult.get("payment");
        if (!(payment instanceof Map<?, ?> map)) {
            return false;
        }
        return Set.of("SUCCESS", "FAILED", "CLOSED", "REFUNDED", "PARTIAL_REFUNDED")
                .contains(String.valueOf(map.get("status")));
    }

    /** 扁平参数构造（跳过 null；服务端 canonical 同样跳过 null，签名口径一致）。 */
    private static Map<String, Object> params(Object... keyValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValue.length; i += 2) {
            if (keyValue[i + 1] != null) {
                map.put(String.valueOf(keyValue[i]), keyValue[i + 1]);
            }
        }
        return map;
    }

    // ---------- 通用调用 ----------

    public Object call(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(params);
        body.put("appId", appId);
        String path = "v2".equals(apiVersion) && V2_METHODS.contains(method)
                ? "/openapi/v2/" + method : "/openapi/" + method;
        int attempt = 0;
        while (true) {
            Map<String, Object> signed = Signer.sign(body, appSecret, useNonce ? UUID.randomUUID().toString() : null);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Api-Version", apiVersion);
            if (accessToken != null) {
                headers.put("X-Access-Token", accessToken);
            }
            Response response;
            try {
                response = send("POST", path, signed, headers);
            } catch (IOException e) {
                if (attempt++ < retries) {
                    Thread.sleep(300L * attempt);
                    continue;
                }
                throw new OpenApiException("C0001", "网络异常: " + e.getMessage(), 0);
            }
            lastHeaders = response.headers;
            if (response.status == 200) {
                Object data = response.raw;
                if (data instanceof Map<?, ?> map && "v2".equals(map.get("apiVersion")) && map.containsKey("data")) {
                    return map.get("data");
                }
                return data;
            }
            String code = String.valueOf(response.body.getOrDefault("code", "B0002"));
            String message = String.valueOf(response.body.getOrDefault("error", "调用失败"));
            boolean retryable = response.status >= 500 || "B0003".equals(code) || "C0001".equals(code);
            if (retryable && attempt++ < retries) {
                Thread.sleep(300L * attempt);
                continue;
            }
            throw new OpenApiException(code, message, response.status);
        }
    }

    private Response send(String method, String path, Map<String, Object> body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> responseHeaders.put(key, String.join(",", values)));
        Object raw = Json.parse(response.body());
        Map<String, Object> object = raw instanceof Map ? asMap(raw) : new LinkedHashMap<>();
        return new Response(response.statusCode(), raw, object, responseHeaders);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalStateException("期望对象响应，实际: " + value);
    }

    private record Response(int status, Object raw, Map<String, Object> body, Map<String, String> headers) {
    }

    /** 错误码映射异常（A0400/A0401/A0403/A0409/A0429/B0003/C0001...）。 */
    public static class OpenApiException extends Exception {
        private final String code;
        private final int status;

        public OpenApiException(String code, String message, int status) {
            super("[" + code + "] " + message + " (http=" + status + ")");
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    /** 便于示例打印 scope 清单。 */
    public static List<String> frozenMethods() {
        return List.of("account.open", "fund.topup", "fund.withdraw", "account.transfer",
                "account.balance", "trade.create", "trade.pay", "trade.query");
    }

    /**
     * C-5 收银台 9 方法目录（<b>契约单一事实来源</b>，verify-multiclient.py 据此与
     * OpenApiCashierController 实际端点做一一对应自检，防漂移）。
     */
    public static List<String> cashierMethodCatalog() {
        return List.of("cashier.methods", "cashier.code.create", "cashier.code.parse",
                "cashier.code.query", "cashier.scanPay", "cashier.verify",
                "cashier.payVerified", "cashier.payPassword.set", "cashier.sms.send");
    }

    /** CH-6 签约代扣 2 方法目录（对应 OpenApiSignController 实际端点）。 */
    public static List<String> signMethodCatalog() {
        return List.of("channel.sign", "channel.agent.pay");
    }
}