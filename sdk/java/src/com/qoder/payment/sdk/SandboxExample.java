package com.qoder.payment.sdk;

import java.util.Map;
import java.util.UUID;

/**
 * G-4b Java SDK 沙箱示例：
 * <pre>
 *   bash sdk/java/build.sh
 *   PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.SandboxExample
 * </pre>
 * 断言：开户 SBXA* / 充值幂等 / 余额裸 long / 下单+BALANCE 支付+查询 / 划转 / 提现。退出码 0=通过。
 */
public final class SandboxExample {

    public static void main(String[] args) throws Exception {
        String base = env("PP_GATEWAY", "http://127.0.0.1:8080");
        String appId = System.getenv("PP_APP_ID");
        String secret = System.getenv("PP_APP_SECRET");
        if (appId == null || secret == null) {
            System.out.println("需要 PP_APP_ID / PP_APP_SECRET");
            System.exit(2);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        PaymentOpenApiClient client = new PaymentOpenApiClient(base, appId, secret);

        Map<String, Object> version = client.version();
        System.out.println("[sdk-java] 版本公告 default=" + version.get("default") + " current=" + version.get("current"));

        Map<String, Object> account = client.accountOpen("OPTEST-JSDK-" + suffix, "USER");
        String accountId = String.valueOf(account.get("accountId"));
        require(accountId.startsWith("SBXA"), "沙箱账户 id 前缀应为 SBXA: " + accountId);
        System.out.println("[sdk-java] 开户 accountId=" + accountId);

        String bizNo = "OPTEST-JSDK-TOPUP-" + suffix;
        Map<String, Object> topup = client.fundTopup(accountId, 1000, bizNo);
        Map<String, Object> payment = cast(topup.get("payment"));   // v1 冻结形状 {payment, repeated}
        require("SUCCESS".equals(payment.get("status")), "充值应 SUCCESS: " + topup);
        Map<String, Object> replay = cast(client.fundTopup(accountId, 1000, bizNo).get("payment"));
        require(payment.get("id").equals(replay.get("id")), "bizNo 幂等失败");
        require(client.accountBalance(accountId) == 1000L, "余额应为 1000");
        System.out.println("[sdk-java] 充值+幂等+余额 paymentId=" + payment.get("id"));

        Map<String, Object> order = client.tradeCreate("OPTEST-JSDK 行程", 300, "OPTEST-JSDK-ORDER-" + suffix);
        require("CREATED".equals(order.get("status")), "下单应 CREATED: " + order);
        Map<String, Object> paid = cast(client.tradePay(String.valueOf(order.get("id")), accountId, "BALANCE").get("payment"));
        require("SUCCESS".equals(paid.get("status")), "支付应 SUCCESS: " + paid);
        Map<String, Object> queried = client.tradeQuery(String.valueOf(order.get("id")));
        Map<String, Object> queriedOrder = cast(queried.get("order"));
        require("SUCCESS".equals(queriedOrder.get("status")), "查询订单应 SUCCESS: " + queried);
        require(client.accountBalance(accountId) == 700L, "支付后余额应为 700");
        System.out.println("[sdk-java] 下单+支付+查询 orderId=" + order.get("id"));

        Map<String, Object> peer = client.accountOpen("OPTEST-JSDK-PEER-" + suffix, "USER");
        client.accountTransfer(accountId, String.valueOf(peer.get("accountId")), 200, "OPTEST-JSDK-TRANSFER-" + suffix);
        require(client.accountBalance(accountId) == 500L, "划转后余额应为 500");
        client.fundWithdraw(accountId, 100, "OPTEST-JSDK-WITHDRAW-" + suffix);
        require(client.accountBalance(accountId) == 400L, "提现后余额应为 400");
        System.out.println("[sdk-java] 划转/提现完成，余额=400");
        System.out.println("Java SDK 沙箱示例全部断言通过");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}