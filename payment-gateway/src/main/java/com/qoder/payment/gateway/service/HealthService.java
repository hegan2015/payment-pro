package com.qoder.payment.gateway.service;

import com.qoder.payment.facade.HealthFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 健康聚合：注册中心 HTTP 探针 + 各域 Dubbo ping。 */
@Slf4j
@Service
public class HealthService {

    @DubboReference(group = "payment-member", timeout = 1500)
    private HealthFacade memberHealth;

    @DubboReference(group = "payment-account", timeout = 1500)
    private HealthFacade accountHealth;

    @DubboReference(group = "payment-accounting", timeout = 1500)
    private HealthFacade accountingHealth;

    @DubboReference(group = "payment-trade", timeout = 1500)
    private HealthFacade tradeHealth;

    @DubboReference(group = "payment-payment", timeout = 1500)
    private HealthFacade paymentHealth;

    @DubboReference(group = "payment-cashier", timeout = 1500)
    private HealthFacade cashierHealth;

    @DubboReference(group = "payment-channel", timeout = 1500)
    private HealthFacade channelHealth;

    @DubboReference(group = "payment-risk", timeout = 1500)
    private HealthFacade riskHealth;

    @DubboReference(group = "payment-settle", timeout = 1500)
    private HealthFacade settleHealth;

    @DubboReference(group = "payment-notify", timeout = 1500)
    private HealthFacade notifyHealth;

    @DubboReference(group = "payment-recon", timeout = 1500)
    private HealthFacade reconHealth;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(row("gateway", 8080, true, "开放网关"));
        result.add(row("registry", 2181, registryUp(), "注册中心 ZooKeeper"));
        result.add(row("member", 20881, ping(memberHealth), "会员域"));
        result.add(row("account", 20882, ping(accountHealth), "账户域"));
        result.add(row("accounting", 20883, ping(accountingHealth), "账务域"));
        result.add(row("trade", 20884, ping(tradeHealth), "交易域"));
        result.add(row("payment", 20885, ping(paymentHealth), "支付核心"));
        result.add(row("cashier", 20886, ping(cashierHealth), "收银台"));
        result.add(row("channel", 20887, ping(channelHealth), "渠道网关"));
        result.add(row("risk", 20888, ping(riskHealth), "风控域"));
        result.add(row("settle", 20889, ping(settleHealth), "清结算域"));
        result.add(row("notify", 20890, ping(notifyHealth), "消息通知"));
        result.add(row("recon", 20891, ping(reconHealth), "对账域"));
        return result;
    }

    private boolean ping(HealthFacade facade) {
        try {
            return facade.ping().isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean registryUp() {
        if ("docker".equals(System.getProperty("spring.profiles.active"))) {
            return zkRuok();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + System.getenv().getOrDefault("ZK_HTTP_PORT", "8092") + "/health"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Docker ZooKeeper 四字探针 ruok → imok。 */
    private boolean zkRuok() {
        int port = Integer.parseInt(System.getenv().getOrDefault("ZK_PORT", "2181"));
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            socket.setSoTimeout(800);
            socket.getOutputStream().write("ruok".getBytes());
            socket.getOutputStream().flush();
            byte[] buf = new byte[16];
            int n = socket.getInputStream().read(buf);
            return n > 0 && new String(buf, 0, n).contains("imok");
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> row(String service, int port, boolean ok, String domain) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("port", port);
        row.put("ok", ok);
        row.put("domain", domain);
        return row;
    }
}