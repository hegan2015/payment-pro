package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.gateway.web.GatewaySupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-4a OAuth2 client_credentials 授权端点（{@code /openapi/oauth/token}，免 Bearer、免 HMAC——
 * 以 appId+appSecret 换 access_token；<b>与 HMAC 并存</b>，业务方法仍必须签名）。
 *
 * <p>本端点在闸门 EXEMPT 名单内（不做 scope/配额校验），但 token 颁发本身校验应用状态：
 * 已撤销应用 → 403，密钥错误 → 401。
 */
@Slf4j
@RestController
@RequestMapping("/openapi/oauth")
@Tag(name = "开放平台授权 (G-4a)", description = "client_credentials：appId/appSecret → access_token（scope 授权）")
public class OpenApiAuthController {

    @Autowired
    private OpenApiAuthService authService;

    @Autowired
    private OpenApiTokenStore tokenStore;

    @PostMapping("/token")
    @Operation(summary = "颁发 access_token", description = "入参 {appId, appSecret, scope?}；scope 空格或数组，缺省=应用全部已授权 scope")
    public ResponseEntity<Object> token(@RequestBody Map<String, Object> body) {
        try {
            String appId = str(body.get("appId"));
            String appSecret = str(body.get("appSecret"));
            List<String> scopes = scopesOf(body.get("scope"));
            return ResponseEntity.ok(authService.issueToken(appId, appSecret, scopes));
        } catch (BizException e) {
            return ResponseEntity.status(GatewaySupport.statusOf(e.getCode()))
                    .body(GatewaySupport.errorBody(e.getMessage(), e.getCode()));
        }
    }

    @PostMapping("/introspect")
    @Operation(summary = "token 自省（active/appId/scope/exp）")
    public ResponseEntity<Object> introspect(@RequestBody Map<String, Object> body) {
        OpenApiTokenStore.TokenEntry entry = tokenStore.validate(str(body.get("token")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", entry != null);
        if (entry != null) {
            result.put("appId", entry.getAppId());
            result.put("scope", String.join(" ", entry.getScopes() == null ? List.of() : entry.getScopes()));
            result.put("exp", entry.getExpiresAt() / 1000);
        }
        return ResponseEntity.ok(result);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopesOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return ((List<Object>) list).stream().map(String::valueOf).toList();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return Arrays.stream(text.split("[\\s,]+")).filter(s -> !s.isBlank()).toList();
    }
}