package com.qoder.payment.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * G-2 API 文档（springdoc-openapi，零外部依赖，local/docker 同构）。
 *
 * <p>分组：
 * <ul>
 *   <li>{@code console} —— 控制台 REST {@code /api/**}（Bearer 会话 + RBAC 权限点 + 敏感操作 step-up）；</li>
 *   <li>{@code merchant} —— 商户 OpenAPI {@code /openapi/**}（appId + HMAC-SHA256 验签，<b>免 Bearer</b>，§0.7 红线）。</li>
 * </ul>
 *
 * <p>端点：{@code /v3/api-docs}（总）、{@code /v3/api-docs/console}、{@code /v3/api-docs/merchant}、
 * {@code /swagger-ui.html}。文档端点免鉴权（Security 链 anyRequest permitAll），
 * 但<b>敏感 schema 一律剔除</b>：appSecret/graceSecrets/password/privateKey/token 等属性不出现在文档，
 * 形如长 hex/base64 的 example/default 示例值一并抹除（{@link #sensitiveSchemaScrubber()}）。
 *
 * <p>错误码体系（G-3）随文档描述输出，码表单一事实来源 {@code ErrorCodeRegistry}。
 */
@Slf4j
@Configuration
public class OpenApiDocConfig {

    /** 敏感属性名（小写比对）：永不出现在文档 schema。 */
    private static final Set<String> SENSITIVE_PROPERTIES = Set.of(
            "appsecret", "gracesecrets", "password", "oldpassword", "newpassword", "secret",
            "secretkey", "signkey", "privatekey", "certprivatekey", "platformsecret",
            "token", "accesstoken", "refreshtoken", "apikey", "credential", "credentials",
            "stepuptoken", "sessiontoken");

    private static final String CONSOLE_DESCRIPTION = """
            支付平台控制台 REST API（/api/**）。

            **认证**：`Authorization: Bearer <token>`，token 由 `POST /api/auth/login` 颁发
            （会话外置：docker Redis / local 内存）。未登录 401 `A0401`，权限点不足 403 `A0403`。

            **敏感操作二次认证（S-7）**：大额退款/冲正、复核 approve/reject、备付金调整、密钥轮换、
            通知重发（单条/批量）需额外携带 `X-Step-Up-Token`（`POST /api/security/step-up` 用
            **当前登录账号自己的口令**再验颁发，默认 300s）。缺失/失效 → 403 `A0403`。
            大额退款/冲正另需双人复核 APPROVED，否则 400 `A0428`（绝不动账）。

            **响应约定**：成功为裸数据（不套 envelope）；失败统一 `{error, code, traceId}`。
            金额一律 `long` 型「分」。

            **免鉴权端点**：`/api/health`、`/api/auth/login`、`/api/auth/logout`、`/actuator/**`、本文档端点。

            ### 错误码表（G-3）
            """;

    private static final String MERCHANT_DESCRIPTION = """
            商户开放 API（/openapi/**），**免 Bearer**，以 appId + HMAC-SHA256 签名接入。

            **签名算法（冻结，逐字节不变）**：
            1. 取请求 body 全部字段（**剔除 `sign`**，`null` 值跳过），按 key 的 `TreeMap` 自然序拼 `k=v&k=v`；
            2. `sign = HMAC-SHA256(appSecret, canonical)` 取 **lowercase hex**；
            3. `timestamp` 为**毫秒**，服务端校验 ±300s 窗；
            4. 可选 `nonce`（≤64 字符）防重放，TTL 300s；**不传则完全跳过**（兼容既有调用方）；
            5. 可选 `sign_type=RSA2` 时用商户证书公钥 `SHA256withRSA` 验签（base64）。

            **治理**：appId 热点参数 QPS 限流 → 429 `A0429`；下游熔断 → 503 `B0003`；
            全部拒绝发生在业务受理之前（不产生在途支付单）。

            **兼容红线**：`account.open / fund.topup / fund.withdraw / account.transfer / account.balance /
            trade.create / trade.pay / trade.query` 八个方法入参、返回、幂等键与语义冻结；
            `account.balance` 返回**裸数字 long**；`trade.pay` 的 `method=BALANCE` 保持同步受理。
            新增方法（如 `notify.query` / `notify.resend`）只 additive。

            **通知回调**：平台向 `callbackUrl` POST JSON（**支持 http**），签名三要素头 additive：
            `X-Payment-Signature` / `X-Payment-Timestamp` / `X-Payment-Nonce` / `X-Payment-Sign-Type`；
            商户验签式 `HMAC-SHA256(appSecret, rawBody + "\\n" + timestamp + "\\n" + nonce)`。
            `PAY_SUCCESS` / `REFUND_SUCCESS` 的 body 结构冻结不变；新事件类型为统一 envelope
            `{event, bizId, timestamp, payload}`。

            ### 错误码表（G-3）
            """;

    @Bean
    public OpenAPI paymentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("payment-pro API")
                        .version("1.0.0")
                        .description("领域化支付平台：控制台 API 与商户 OpenAPI 分组文档。"
                                + "金额单位统一为「分」(long)。错误码三段式 A/B/C，详见各分组描述。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque-token")
                                .description("控制台会话 token（POST /api/auth/login 颁发）")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    public GroupedOpenApi consoleApiGroup() {
        return GroupedOpenApi.builder()
                .group("console")
                .pathsToMatch("/api/**")
                .addOpenApiCustomizer(sensitiveSchemaScrubber())
                .addOpenApiCustomizer(openApi -> appendDescription(openApi, CONSOLE_DESCRIPTION))
                .build();
    }

    @Bean
    public GroupedOpenApi merchantApiGroup() {
        return GroupedOpenApi.builder()
                .group("merchant")
                .pathsToMatch("/openapi/**")
                .addOpenApiCustomizer(sensitiveSchemaScrubber())
                .addOpenApiCustomizer(openApi -> {
                    appendDescription(openApi, MERCHANT_DESCRIPTION);
                    // 商户端不用 Bearer：移除全局 security 要求，避免误导在线调试
                    openApi.setSecurity(List.of());
                    if (openApi.getComponents() != null) {
                        openApi.getComponents().setSecuritySchemes(null);
                    }
                })
                .build();
    }

    private static void appendDescription(OpenAPI openApi, String text) {
        if (openApi.getInfo() == null) {
            openApi.setInfo(new Info());
        }
        String existing = openApi.getInfo().getDescription() == null ? "" : openApi.getInfo().getDescription();
        openApi.getInfo().setDescription(text + "\n" + com.qoder.payment.gateway.web.ErrorCodeRegistry.markdown()
                + "\n" + existing);
    }

    /**
     * 敏感 schema 清洗：剔除密钥/口令/token 类属性，抹除形如长 hex/base64 的示例值。
     * 文档免鉴权可访问，故必须在<b>生成期</b>而非渲染期脱敏。
     */
    @Bean
    public OpenApiCustomizer sensitiveSchemaScrubber() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            int removed = 0;
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                removed += scrub(schema);
            }
            if (removed > 0) {
                log.debug("G-2 文档脱敏：剔除敏感属性 {} 项", removed);
            }
        };
    }

    @SuppressWarnings("rawtypes")
    private static int scrub(Schema<?> schema) {
        if (schema == null) {
            return 0;
        }
        int removed = 0;
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            Iterator<Map.Entry<String, Schema>> it = properties.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Schema> entry = it.next();
                String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (SENSITIVE_PROPERTIES.contains(name)) {
                    it.remove();
                    removed++;
                    continue;
                }
                removed += scrub(entry.getValue());
            }
        }
        if (looksSecret(schema.getExample()) || looksSecret(schema.getDefault())) {
            schema.setExample(null);
            schema.setDefault(null);
            removed++;
        }
        if (schema.getItems() != null) {
            removed += scrub(schema.getItems());
        }
        return removed;
    }

    /** 长 hex/base64（≥32 字符）视为密钥样示例值，一律抹除。 */
    private static boolean looksSecret(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.length() >= 32 && trimmed.matches("[A-Za-z0-9+/=_-]+");
    }
}