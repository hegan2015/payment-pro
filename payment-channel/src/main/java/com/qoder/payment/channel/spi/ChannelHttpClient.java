package com.qoder.payment.channel.spi;

import com.qoder.payment.common.secret.SecretProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * CH-2b 渠道 HTTP 客户端：超时 / 重试 / 签名（HMAC-SHA256，密钥经 S-1 SecretProvider，明文不落日志）。
 *
 * <p><b>沙箱边界（如实说明）</b>：本客户端当前只指向<b>本进程内的模拟沙箱银行端点</b>
 * （{@code channel.sandbox.base-url}，默认 http://127.0.0.1:18087），是真实 HTTP 往返但
 * <b>不连接任何真实银行/支付机构</b>，仓库内无任何真实渠道凭据。接生产渠道时只需替换
 * base-url + 密钥 ref + 报文/验签实现（同 SPI），并先在沙箱跑通混沌（§5.3 切换步骤）。
 *
 * <p>语义护栏：连接被拒/发送前失败 = 确未受理（可故障切换）；读超时 = 状态未知
 * （{@code ioError=true, status=0}）→ 调用方必须返回 PROCESSING 交查询补单，绝不臆断失败。
 * 重试只对幂等 GET 生效（受理类 POST 不自动重试，避免重复受理）。
 */
@Slf4j
@Component
public class ChannelHttpClient {

    /** 渠道通讯结果：status=0 表示 IO 异常/超时（状态未知）。 */
    public record HttpResult(int status, String body, boolean ioError, String error) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 确未受理：连接被拒/DNS 失败等发送前失败（可安全切换备选渠道）。 */
        public boolean notAccepted() {
            return ioError && error != null && error.contains("CONNECT");
        }
    }

    private final SecretProvider secrets;
    private final HttpClient client;

    @Value("${payment.channel.http.connect-timeout-ms:2000}")
    private long connectTimeoutMs;

    public ChannelHttpClient(SecretProvider secrets) {
        this.secrets = secrets;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(2000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpResult postJson(String url, String json, String secretRef, long timeoutMs) {
        return send("POST", url, json, secretRef, timeoutMs);
    }

    public HttpResult get(String url, String secretRef, long timeoutMs) {
        return send("GET", url, null, secretRef, timeoutMs);
    }

    private HttpResult send(String method, String url, String body, String secretRef, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : 2000L;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Content-Type", "application/json;charset=UTF-8");
            String payload = body == null ? "" : body;
            if (secretRef != null && !secretRef.isBlank()) {
                String timestamp = String.valueOf(System.currentTimeMillis());
                builder.header("X-Channel-Timestamp", timestamp);
                builder.header("X-Channel-Sign", sign(secretRef, timestamp, payload));
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), false, null);
        } catch (java.net.ConnectException e) {
            log.warn("渠道连接被拒（确未受理）url={}: {}", url, e.getMessage());
            return new HttpResult(0, null, true, "CONNECT_REFUSED: " + e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("渠道 IO 异常（状态未知，不臆断失败）url={}: {}", url, e.getMessage());
            return new HttpResult(0, null, true, "IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpResult(0, null, true, "INTERRUPTED");
        } catch (RuntimeException e) {
            log.warn("渠道请求异常 url={}: {}", url, e.toString());
            return new HttpResult(0, null, true, "CONNECT: " + e.getMessage());
        }
    }

    /** HMAC-SHA256 签名：TreeMap 排序 k=v&（与平台 OpenAPI 验签口径同构），密钥经 SecretProvider。 */
    public String sign(String secretRef, String timestamp, String payload) {
        String secret = secrets.getSecret(secretRef);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("渠道密钥缺失（SecretProvider ref=" + secretRef + "），拒绝无签名通讯");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("timestamp", timestamp);
        params.put("payload", payload);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return hmac(secret, sb.toString());
    }

    public boolean verify(String secretRef, String timestamp, String payload, String sign) {
        if (sign == null || sign.isBlank() || timestamp == null) {
            return false;
        }
        try {
            String expect = sign(secretRef, timestamp, payload);
            return java.security.MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                    sign.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("渠道验签失败 ref={}: {}", secretRef, e.getMessage());
            return false;
        }
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("渠道签名计算失败: " + e.getMessage(), e);
        }
    }
}