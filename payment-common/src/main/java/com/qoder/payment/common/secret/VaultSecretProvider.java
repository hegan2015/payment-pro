package com.qoder.payment.common.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Vault KV v2 密钥实现（S-1 收尾，docker 可选 {@code payment.secret.vault.enabled=true}）。
 * <ul>
 *   <li>读取路径 {@code <mount>/data/<prefix>/<ref>}，密钥值取 {@code data.data.value}
 *       （兼容 {@code secret}/{@code key} 字段）；私钥取 {@code data.data.privateKey}（PKCS#8 Base64/PEM），
 *       证书取 {@code data.data.cert}（PEM）。</li>
 *   <li><b>配置缺失自动降级</b>：url/token 任一为空 → 委托 {@link LocalFileSecretProvider}，
 *       local 零依赖不报错（任务约定；与「已配置但不可达 → fail-fast」区分）。</li>
 *   <li>已配置但 Vault 不可达/读取失败 → 抛 {@link IllegalStateException}，验签侧 fail-fast（安全优先）。</li>
 * </ul>
 * 密钥明文绝不写日志。token 走 {@code ~/.config/payment-pro/docker.env}（VAULT_TOKEN），不落仓库。
 */
@Slf4j
public class VaultSecretProvider implements SecretProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final String token;
    private final String mount;
    private final String prefix;
    private final LocalFileSecretProvider fallback;
    private final HttpClient http;

    public VaultSecretProvider(String url, String token, String mount, String prefix) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
        this.mount = mount == null || mount.isBlank() ? "secret" : mount.trim();
        this.prefix = prefix == null || prefix.isBlank() ? "payment" : prefix.trim();
        this.fallback = new LocalFileSecretProvider();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        if (degraded()) {
            log.warn("Vault 配置缺失（url/token 为空），SecretProvider 自动降级本地文件实现（零依赖）");
        } else {
            log.info("Vault SecretProvider 启用 url={} mount={} prefix={}", this.url, this.mount, this.prefix);
        }
    }

    /** 配置缺失 → 降级本地文件。 */
    public boolean degraded() {
        return url.isBlank() || token.isBlank();
    }

    @Override
    public String getSecret(String ref) {
        if (degraded()) {
            return fallback.getSecret(ref);
        }
        JsonNode data = read(ref);
        if (data == null) {
            return null;
        }
        for (String field : new String[]{"value", "secret", "key"}) {
            if (data.hasNonNull(field)) {
                return data.get(field).asText();
            }
        }
        return null;
    }

    @Override
    public PrivateKey getPrivateKey(String ref) {
        if (degraded()) {
            return fallback.getPrivateKey(ref);
        }
        JsonNode data = read(ref);
        if (data == null || !data.hasNonNull("privateKey")) {
            return null;
        }
        try {
            String pem = data.get("privateKey").asText();
            byte[] der = pem.contains("-----BEGIN")
                    ? Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""))
                    : Base64.getDecoder().decode(pem.replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Vault 私钥解析失败 ref=" + ref, e);
        }
    }

    @Override
    public X509Certificate getCert(String ref) {
        if (degraded()) {
            return fallback.getCert(ref);
        }
        JsonNode data = read(ref);
        if (data == null || !data.hasNonNull("cert")) {
            return null;
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            data.get("cert").asText().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Vault 证书解析失败 ref=" + ref, e);
        }
    }

    /** KV v2 读取；404 → null（密钥不存在）；其它错误 fail-fast。 */
    private JsonNode read(String ref) {
        String path = url + "/v1/" + mount + "/data/" + prefix + "/" + ref;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(path))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Vault-Token", token)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Vault 读取失败 ref=" + ref + " status=" + response.statusCode());
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.path("data").path("data");
            return data.isMissingNode() || data.isNull() ? null : data;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 已配置但不可达：fail-fast（安全优先，绝不静默降级明文）。
            throw new IllegalStateException("Vault 不可达 ref=" + ref + ": " + e.getMessage(), e);
        }
    }
}