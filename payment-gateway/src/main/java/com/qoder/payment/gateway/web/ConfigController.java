package com.qoder.payment.gateway.web;

import com.qoder.payment.common.config.ApolloDynamicConfig;
import com.qoder.payment.common.config.ConfigKeys;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.config.LocalFileDynamicConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置中心控制台（I-2）：
 * <ul>
 *   <li>{@code GET /api/config}：只读当前生效配置（provider/降级状态/生效值/默认值/原始覆盖项），
 *       <b>密钥类键脱敏</b>（{@link ConfigKeys#sensitive}），需登录（安全链 /api/** 兜底）；</li>
 *   <li>{@code POST /api/config/refresh}：local 手动 reload；RBAC 保护——
 *       需权限点 {@code config:write} 或 ADMIN 通配 {@code *}（控制器内校验，
 *       不改 SecurityConfig；未登录由安全链 401，越权此处 403 A0403）。</li>
 * </ul>
 * 返回裸数据，与网关现有风格一致。/openapi/** 不受影响（§0.7 兼容红线）。
 */
@RestController
public class ConfigController {

    /** 刷新配置权限点（ADMIN 通配 * 天然持有；其它角色默认不授予）。 */
    public static final String PERMISSION_REFRESH = "config:write";

    private final DynamicConfig dynamicConfig;

    public ConfigController(DynamicConfig dynamicConfig) {
        this.dynamicConfig = dynamicConfig;
    }

    @GetMapping("/api/config")
    public ResponseEntity<Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", dynamicConfig.provider());
        body.put("apolloEnabled", dynamicConfig instanceof ApolloDynamicConfig);
        if (dynamicConfig instanceof LocalFileDynamicConfig local) {
            body.put("file", local.file().toString());
            body.put("fileExists", local.fileExists());
            body.put("lastReloadAt", local.lastReloadAt());
            body.put("reloadIntervalSeconds", LocalFileDynamicConfig.RELOAD_INTERVAL_SECONDS);
        } else if (dynamicConfig instanceof ApolloDynamicConfig apollo) {
            body.put("appId", apollo.appId());
            body.put("meta", apollo.meta());
            body.put("namespaces", apollo.namespaces());
            body.put("remoteLoaded", apollo.remoteLoaded());
            body.put("initError", apollo.initError());
        }
        // 生效值（含代码默认值兜底）——默认值与改造前逐一相同（§0.7 口径红线）
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put(ConfigKeys.PAYMENT_FEE_BPS,
                dynamicConfig.getInt(ConfigKeys.PAYMENT_FEE_BPS, ConfigKeys.DEFAULT_FEE_BPS));
        effective.put(ConfigKeys.RISK_SINGLE_LIMIT,
                dynamicConfig.getLong(ConfigKeys.RISK_SINGLE_LIMIT, ConfigKeys.DEFAULT_RISK_SINGLE_LIMIT));
        effective.put(ConfigKeys.RISK_WINDOW_MILLIS,
                dynamicConfig.getLong(ConfigKeys.RISK_WINDOW_MILLIS, ConfigKeys.DEFAULT_RISK_WINDOW_MILLIS));
        effective.put(ConfigKeys.RISK_WINDOW_MAX,
                dynamicConfig.getInt(ConfigKeys.RISK_WINDOW_MAX, ConfigKeys.DEFAULT_RISK_WINDOW_MAX));
        body.put("effective", effective);
        body.put("defaults", ConfigKeys.defaults());
        body.put("overrides", mask(dynamicConfig.snapshot()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/config/refresh")
    public ResponseEntity<Object> refresh() {
        if (!hasRefreshPermission()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权限执行该操作", "code", "A0403"));
        }
        boolean refreshed = dynamicConfig.refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", dynamicConfig.provider());
        body.put("refreshed", refreshed);
        body.put("overrides", mask(dynamicConfig.snapshot()));
        return ResponseEntity.ok(body);
    }

    private static boolean hasRefreshPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
                PERMISSION_REFRESH.equals(a.getAuthority()) || "*".equals(a.getAuthority()));
    }

    /** 密钥类键掩码（明文不出网关）；其余键原值展示。 */
    static Map<String, String> mask(Map<String, String> raw) {
        Map<String, String> masked = new TreeMap<>();
        raw.forEach((k, v) -> masked.put(k, ConfigKeys.sensitive(k) ? "******" : v));
        return masked;
    }
}