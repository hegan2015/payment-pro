package com.qoder.payment.sdk;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * C-5 多端收银台 + CH-6 签约代扣：Java SDK 端到端<b>沙箱</b>示例（退出码 0=全断言通过）。
 *
 * <pre>
 *   bash sdk/java/build.sh
 *   PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.CashierSandboxExample
 * </pre>
 *
 * <p>演示<b>业务方自渲染收银台</b>的完整对接序列（payment-pro 不提供 C 端页面）：
 * 开户 → 充值(幂等) → 下单 → cashier.methods 渲染 → 设口令 → verify(PASSWORD) → payVerified
 * → 轮询终态 → 同 requestId 幂等重放 → 小额免密 → 被扫 → 主扫(qrContent 归一 code=)
 * → SMS/BIO → channel.sign → channel.agent.pay(幂等) → 解约后拒绝代扣。
 *
 * <p>资金全程只在沙箱账本（SBXA/SBXO/SBXP），fee=0、同步 SUCCESS，绝不触碰生产资金。
 * 多端适配指南：{@code docs/cashier-multiclient.md}。
 */
public final class CashierSandboxExample {

    private static final String PASSWORD = "135790";
    private static final long FREE_LIMIT = 1000L;

    public static void main(String[] args) throws Exception {
        String base = env("PP_GATEWAY", "http://127.0.0.1:8080");
        String appId = System.getenv("PP_APP_ID");
        String secret = System.getenv("PP_APP_SECRET");
        if (appId == null || secret == null) {
            System.out.println("需要 PP_APP_ID / PP_APP_SECRET（沙箱应用注册响应一次性返回）");
            System.exit(2);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tag = "MCTEST-JSDKCS-" + suffix;
        PaymentOpenApiClient client = new PaymentOpenApiClient(base, appId, secret);

        // ---- 0. 能力公告：收银台/签约方法已登记沙箱 ----
        Map<String, Object> version = client.version();
        Map<String, Object> sandbox = cast(version.get("sandbox"));
        List<?> sandboxMethods = (List<?>) sandbox.get("methods");
        require(sandboxMethods.containsAll(PaymentOpenApiClient.cashierMethodCatalog()),
                "沙箱未登记收银台 9 方法: " + sandboxMethods);
        require(sandboxMethods.containsAll(PaymentOpenApiClient.signMethodCatalog()),
                "沙箱未登记签约 2 方法: " + sandboxMethods);
        require(cast(version.get("multiclient")).get("qrNormalization") != null, "版本公告缺主扫归一口径");
        step("能力公告 default=" + version.get("default") + " 沙箱方法数=" + sandboxMethods.size());

        // ---- 1. 开户 + 充值（bizNo 幂等） ----
        Map<String, Object> payer = client.accountOpen(tag + "-PAYER", "USER");
        String payerId = String.valueOf(payer.get("accountId"));
        require(payerId.startsWith("SBXA"), "沙箱账户前缀应为 SBXA: " + payerId);
        String topupNo = tag + "-TOPUP";
        Map<String, Object> topup = cast(client.fundTopup(payerId, 20000L, topupNo).get("payment"));
        require("SUCCESS".equals(topup.get("status")) && num(topup.get("fee")) == 0L, "充值应 SUCCESS/fee=0");
        Map<String, Object> topupReplay = client.fundTopup(payerId, 20000L, topupNo);
        require(Boolean.TRUE.equals(topupReplay.get("repeated"))
                && cast(topupReplay.get("payment")).get("id").equals(topup.get("id")), "bizNo 幂等失败");
        require(client.accountBalance(payerId) == 20000L, "充值后余额应为 20000");
        step("开户+充值 accountId=" + payerId + " 余额=20000（幂等复放同单）");

        // ---- 2. 下单 → 渲染 ----
        Map<String, Object> order = client.tradeCreate("MCTEST Java 收银台行程", 1500L, tag + "-ORDER-A");
        String orderId = String.valueOf(order.get("id"));
        String merchantId = String.valueOf(order.get("merchantId"));
        Map<String, Object> view = client.cashierMethods(orderId, payerId);
        require(num(view.get("actualAmount")) == 1500L && num(view.get("freeVerifyLimit")) == FREE_LIMIT,
                "渲染金额/免密限额不符: " + view);
        List<?> methods = (List<?>) view.get("methods");
        Map<String, Object> balanceMethod = findMethod(methods, "BALANCE");
        require(Boolean.TRUE.equals(balanceMethod.get("available"))
                && "REAL".equals(balanceMethod.get("category")), "BALANCE 应可用: " + balanceMethod);
        require(methods.stream().anyMatch(m -> "RESERVED".equals(cast(m).get("category"))
                && Boolean.FALSE.equals(cast(m).get("available"))), "应含 RESERVED 不可用方式");
        step("渲染 orderId=" + orderId + " 方式数=" + methods.size());

        // ---- 3. 设口令 → 验证 → 受理 → 轮询终态 → 幂等重放 ----
        require(client.cashierPayPasswordSet(PASSWORD, payerId, null, null), "设置支付口令应返回 true");
        Map<String, Object> verified = client.cashierVerify(payerId, null, orderId, 1500L,
                "PASSWORD", PASSWORD, null, null, null);
        require(Boolean.TRUE.equals(verified.get("pass")) && verified.get("verifyToken") != null,
                "口令验证应通过: " + verified);
        String requestId = PaymentOpenApiClient.newRequestId("PAY");
        Map<String, Object> paidResult = client.cashierPayVerified(orderId, requestId, "BALANCE", payerId,
                String.valueOf(verified.get("verifyToken")), null, null, null);
        Map<String, Object> paid = cast(paidResult.get("payment"));
        require("SUCCESS".equals(paid.get("status")) && Boolean.FALSE.equals(paidResult.get("repeated")),
                "受理应 SUCCESS: " + paidResult);
        Map<String, Object> terminal = client.waitOrderTerminal(orderId, 30000L, 500L);
        require("SUCCESS".equals(cast(terminal.get("order")).get("status")), "订单应终态 SUCCESS");
        require(cast(terminal.get("payment")).get("id").equals(paid.get("id")), "轮询支付单应同一笔");
        Map<String, Object> again = client.cashierPayVerified(orderId, requestId, "BALANCE", payerId,
                null, null, null, null);
        require(Boolean.TRUE.equals(again.get("repeated"))
                && cast(again.get("payment")).get("id").equals(paid.get("id")), "requestId 幂等重放失败");
        require(client.accountBalance(payerId) == 18500L, "幂等重放不得重复扣款");
        step("验证+受理+轮询终态 paymentId=" + paid.get("id") + " 幂等重放同单，余额=18500");

        // ---- 4. 小额免密 ----
        Map<String, Object> small = client.tradeCreate("MCTEST Java 免密行程", 100L, tag + "-ORDER-B");
        Map<String, Object> smallPaid = client.cashierPayVerified(String.valueOf(small.get("id")),
                PaymentOpenApiClient.newRequestId("PAY"), "BALANCE", payerId, null, null, null, null);
        require("SUCCESS".equals(cast(smallPaid.get("payment")).get("status")), "小额免密应成功");
        require(client.accountBalance(payerId) == 18400L, "免密后余额应为 18400");
        step("小额免密受理成功（100 ≤ 1000），余额=18400");

        // ---- 5. 被扫 ----
        Map<String, Object> scanOrder = client.tradeCreate("MCTEST Java 被扫行程", 100L, tag + "-ORDER-C");
        Map<String, Object> userCode = client.cashierCodeCreate("USER_PRESENTED", payerId,
                String.valueOf(scanOrder.get("id")), null, null);
        String codeValue = String.valueOf(userCode.get("code"));
        require(codeValue.length() == 18 && codeValue.chars().allMatch(Character::isDigit),
                "被扫码应为 18 位数字: " + codeValue);
        require(codeValue.equals(userCode.get("qrContent")), "被扫 qrContent 应等于码值本身");
        require(codeValue.equals(PaymentOpenApiClient.normalizeScanCode(String.valueOf(userCode.get("qrContent")))),
                "被扫归一 should be identity");
        require("ACTIVE".equals(client.cashierCodeParse(codeValue).get("status")), "解析码应 ACTIVE");
        String scanRid = PaymentOpenApiClient.newRequestId("SCAN");
        Map<String, Object> scanned = cast(client.cashierScanPay(codeValue, scanRid, null, "BALANCE",
                null, null, null, null).get("payment"));
        require("SUCCESS".equals(scanned.get("status")), "被扫受理应 SUCCESS: " + scanned);
        require("CONSUMED".equals(client.waitCodeConsumed(codeValue, 30000L, 500L).get("status")),
                "码应轮询至 CONSUMED");
        require(Boolean.TRUE.equals(client.cashierScanPay(codeValue, scanRid, null, "BALANCE",
                null, null, null, null).get("repeated")), "同 requestId 核销应幂等");
        expectError("A0409", () -> client.cashierScanPay(codeValue, PaymentOpenApiClient.newRequestId("SCAN"),
                null, "BALANCE", null, null, null, null), "已核销码异 requestId 应 409 防重放");
        require(client.accountBalance(payerId) == 18300L, "被扫后余额应为 18300");
        step("被扫核销 paymentId=" + scanned.get("id") + " 一次性+幂等+防重放，余额=18300");

        // ---- 6. 主扫（qrContent 是 URL，必须归一取 code=） ----
        Map<String, Object> mpOrder = client.tradeCreate("MCTEST Java 主扫行程", 100L, tag + "-ORDER-D");
        Map<String, Object> mpCode = client.cashierCodeCreate("MERCHANT_PRESENTED", null,
                String.valueOf(mpOrder.get("id")), null, null);
        String qrContent = String.valueOf(mpCode.get("qrContent"));
        require(qrContent.startsWith("https://cashier.payment-pro.example/scan?code="),
                "主扫 qrContent 应为扫码 URL: " + qrContent);
        String token = PaymentOpenApiClient.normalizeScanCode(qrContent);
        require(token.equals(mpCode.get("code")) && token.startsWith("MP"), "主扫归一取 token 失败: " + token);
        require("ACTIVE".equals(client.cashierCodeParse(token).get("status")), "归一后解析应 ACTIVE");
        Map<String, Object> mpPaid = cast(client.cashierScanPay(token, PaymentOpenApiClient.newRequestId("SCAN"),
                payerId, "BALANCE", null, null, null, null).get("payment"));
        require("SUCCESS".equals(mpPaid.get("status")) && payerId.equals(mpPaid.get("payerAccountId")),
                "主扫受理应 SUCCESS 且付款方为指定账户: " + mpPaid);
        require(client.accountBalance(payerId) == 18200L, "主扫后余额应为 18200");
        step("主扫归一 token=" + token.substring(0, Math.min(10, token.length())) + "... 受理成功，余额=18200");

        // ---- 7. SMS / BIO ----
        Map<String, Object> masked = client.cashierSmsSend(payerId, null, orderId, null);
        require("******".equals(masked.get("smsCode")) && Boolean.TRUE.equals(masked.get("smsCodeMasked")),
                "默认短信码应掩码: " + masked);
        Map<String, Object> sms = client.cashierSmsSend(payerId, null, orderId, true);
        String smsCode = String.valueOf(sms.get("smsCode"));
        require(smsCode.length() == 6 && smsCode.chars().allMatch(Character::isDigit), "沙箱应返回 6 位明文码");
        Map<String, Object> smsVerify = client.cashierVerify(payerId, null, orderId, 1500L,
                "SMS", null, smsCode, null, null);
        require(Boolean.TRUE.equals(smsVerify.get("pass")) && "SMS".equals(smsVerify.get("challengeType")),
                "SMS 验证应通过: " + smsVerify);
        Map<String, Object> bio = client.cashierVerify(payerId, null, orderId, 1500L,
                "BIO", null, null, "MCTEST-BIO-" + suffix, null);
        require(Boolean.TRUE.equals(bio.get("pass")) && "BIO".equals(bio.get("challengeType")),
                "BIO 验证应通过: " + bio);
        Map<String, Object> bad = client.cashierVerify(payerId, null, orderId, 1500L,
                "PASSWORD", "000000", null, null, null);
        require(Boolean.FALSE.equals(bad.get("pass")) && bad.get("verifyToken") == null,
                "错口令应 pass=false 且不签发令牌: " + bad);
        step("SMS/BIO 验证通过；错口令 pass=false（不受理不扣款）");

        // ---- 8. 签约 → 代扣（幂等）→ 解约后拒绝 ----
        String card = luhnCard();
        String signRid = PaymentOpenApiClient.newRequestId("SIGN");
        Map<String, Object> contract = client.channelSign(signRid, payerId, merchantId, card,
                Map.of("singleLimit", 5000L, "periodLimit", 20000L, "periodType", "DAY",
                        "scopeNote", "MCTEST Java 代扣授权"));
        require("SIGNED".equals(contract.get("status")) && contract.get("signNo") != null,
                "签约应 SIGNED: " + contract);
        require(String.valueOf(contract.get("cardNoMask")).endsWith(card.substring(card.length() - 4))
                && !contract.toString().contains(card), "卡号脱敏失败: " + contract);
        require(Boolean.TRUE.equals(client.channelSign(signRid, payerId, merchantId, card).get("repeated")),
                "签约 requestId 幂等失败");
        String signNo = String.valueOf(contract.get("signNo"));
        String deductRid = PaymentOpenApiClient.newRequestId("DEDUCT");
        Map<String, Object> deduct = client.channelAgentPay(signNo, 500L, deductRid, merchantId,
                "MCTEST Java 代扣", null);
        require("SUCCESS".equals(deduct.get("status")) && deduct.get("deductNo") != null
                && deduct.get("paymentId") != null, "代扣应 SUCCESS: " + deduct);
        Map<String, Object> deductTerminal = client.waitDeductTerminal(signNo, 500L, deductRid, 30000L, 500L);
        require(deductTerminal.get("deductNo").equals(deduct.get("deductNo"))
                && Boolean.TRUE.equals(deductTerminal.get("repeated")), "代扣幂等重放应同单");
        require(client.accountBalance(payerId) == 17700L, "代扣幂等重放不得重复扣款");
        step("签约 signNo=" + signNo + " 代扣 deductNo=" + deduct.get("deductNo") + " 幂等重放同单，余额=17700");

        Map<String, Object> cancelled = client.channelUnsign(signNo, PaymentOpenApiClient.newRequestId("SIGN"),
                "MCTEST Java 示例解约");
        require("CANCELLED".equals(cancelled.get("status")), "解约应 CANCELLED: " + cancelled);
        expectError(null, () -> client.channelAgentPay(signNo, 100L,
                PaymentOpenApiClient.newRequestId("DEDUCT"), merchantId, null, null), "解约后应拒绝代扣");
        require(client.accountBalance(payerId) == 17700L, "拒绝代扣不得动账");
        step("解约 CANCELLED；解约后代扣受理前拒绝，余额不变=17700");

        System.out.println("Java SDK 收银台沙箱示例全部断言通过");
    }

    // ---------- 工具 ----------

    private interface Action {
        void run() throws Exception;
    }

    /** 期望业务拒绝；expectedCode=null 时接受 A0403/A0409/B0001 任一（受理前拒绝，不落悬挂单）。 */
    private static void expectError(String expectedCode, Action action, String message) throws Exception {
        try {
            action.run();
        } catch (PaymentOpenApiClient.OpenApiException e) {
            if (expectedCode != null) {
                require(expectedCode.equals(e.code()), message + "（实际 " + e.code() + "）");
            } else {
                require(List.of("A0403", "A0409", "B0001").contains(e.code()),
                        message + "（实际 " + e.code() + "）");
            }
            return;
        }
        throw new IllegalStateException(message);
    }

    private static Map<String, Object> findMethod(List<?> methods, String method) {
        for (Object item : methods) {
            Map<String, Object> map = cast(item);
            if (method.equals(map.get("method"))) {
                return map;
            }
        }
        throw new IllegalStateException("渲染结果缺支付方式 " + method);
    }

    /** 生成本示例专属 Luhn 合法沙箱卡号（每次不同 ⇒ 不撞风控卡号频率窗口）。 */
    private static String luhnCard() {
        Random random = new Random();
        while (true) {
            StringBuilder body = new StringBuilder("622202");
            for (int i = 0; i < 9; i++) {
                body.append(random.nextInt(10));
            }
            for (int d = 0; d <= 9; d++) {
                String candidate = body + String.valueOf(d);
                if (luhnOk(candidate) && !candidate.endsWith("0000")) {
                    return candidate;
                }
            }
        }
    }

    private static boolean luhnOk(String digits) {
        int total = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            total += d;
            alt = !alt;
        }
        return total % 10 == 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalStateException("期望对象，实际: " + value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void step(String label) {
        System.out.println("[sdk-java-cashier] " + label);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}