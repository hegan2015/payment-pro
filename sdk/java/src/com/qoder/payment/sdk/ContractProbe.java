package com.qoder.payment.sdk;

import java.util.List;

/**
 * C-5 契约一致性探针（供 {@code verify-multiclient.py} 调用，<b>不发起任何网络请求</b>）。
 *
 * <p>输出 Java SDK 的方法目录与主扫归一工具的真实行为，脚本据此与
 * {@code OpenApiCashierController} / {@code OpenApiSignController} 的实际端点做一一对应比对，
 * 并与 Python SDK 的归一结果逐例对齐 —— 防止双 SDK 与服务端契约漂移。
 *
 * <pre>
 *   bash sdk/java/build.sh
 *   java -cp sdk/java/out com.qoder.payment.sdk.ContractProbe
 * </pre>
 */
public final class ContractProbe {

    private ContractProbe() {
    }

    public static void main(String[] args) {
        System.out.println("FROZEN=" + String.join(",", PaymentOpenApiClient.frozenMethods()));
        System.out.println("CASHIER=" + String.join(",", PaymentOpenApiClient.cashierMethodCatalog()));
        System.out.println("SIGN=" + String.join(",", PaymentOpenApiClient.signMethodCatalog()));
        List<String> samples = List.of(
                "https://cashier.payment-pro.example/scan?code=MPabc123&amount=100",
                "https://cashier.payment-pro.example/scan?amount=100&code=MPabc123",
                "https://cashier.payment-pro.example/scan?code=MP%61bc123&amount=100#frag",
                "http://127.0.0.1:8080/scan?code=MPxyz&amount=1&extra=9",
                "code=MPbare&amount=100",
                "123456789012345678",
                "MPabc123",
                "  MPabc123  ",
                "https://cashier.payment-pro.example/scan?amount=100",
                "");
        for (String sample : samples) {
            System.out.println("NORM\t" + sample + "\t" + PaymentOpenApiClient.normalizeScanCode(sample));
        }
        System.out.println("NORM_NULL\t" + PaymentOpenApiClient.normalizeScanCode(null));
        String requestId = PaymentOpenApiClient.newRequestId("PAY");
        System.out.println("RID_PREFIX=" + requestId.startsWith("PAY-"));
        System.out.println("RID_LEN_OK=" + (requestId.length() <= 64));
        System.out.println("CANON_BOOL=" + Signer.canonical(java.util.Map.of("sandbox", true, "a", 1)));
        System.out.println("PROBE_OK");
    }
}