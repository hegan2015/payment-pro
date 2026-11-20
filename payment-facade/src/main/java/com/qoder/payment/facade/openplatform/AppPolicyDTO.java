package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * G-4a 网关内部策略解析出参（<b>不对外暴露</b>，仅供网关授权/限流/计费/沙箱验签使用）。
 *
 * <p>{@code sandboxSecret} 只在 sandbox=true 时填充（沙箱应用密钥密文解密后），
 * 与 member 域 {@code getByAppId} 内部验签链路同一性质；控制台 list/get 走 {@link OpenAppDTO} 脱敏。
 *
 * <p><b>ride 兼容红线</b>：未注册 appId → {@link #legacy(String)}，registered=false、scopes=["*"]、
 * rateLimit=0、dailyQuota=0、unitPrice=0、sandbox=false —— 新授权/配额/计费面全部空转，
 * 既有 HMAC 链与八个冻结方法行为逐字节不变。
 */
@Data
public class AppPolicyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String appId;
    /** 是否已在开放平台注册（false=历史商户，全部新闸门空转）。 */
    private Boolean registered;
    private String status;
    private List<String> scopes;
    private Integer rateLimit;
    private Long dailyQuota;
    private Long unitPrice;
    private Boolean sandbox;
    private String apiVersion;
    /** 仅沙箱应用：验签用明文密钥（内部链路）。 */
    private String sandboxSecret;
    private String merchantId;

    public static AppPolicyDTO legacy(String appId) {
        AppPolicyDTO policy = new AppPolicyDTO();
        policy.setAppId(appId);
        policy.setRegistered(false);
        policy.setStatus("ACTIVE");
        policy.setScopes(List.of(OpenApiScopes.ALL));
        policy.setRateLimit(0);
        policy.setDailyQuota(0L);
        policy.setUnitPrice(0L);
        policy.setSandbox(false);
        policy.setApiVersion("v1");
        return policy;
    }

    public boolean isRegistered() {
        return Boolean.TRUE.equals(registered);
    }

    public boolean isSandbox() {
        return Boolean.TRUE.equals(sandbox);
    }

    public boolean isRevoked() {
        return "REVOKED".equals(status);
    }
}