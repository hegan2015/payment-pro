package com.qoder.payment.facade.openplatform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-4a 开放平台授权范围（scope）目录：<b>API → scope</b> 单一事实来源，member/gateway/SDK 共用。
 *
 * <p>兼容红线：scope 只对<b>已在开放平台注册</b>的 appId 生效；未注册的历史商户（如 ride-pro appM1）
 * 走 {@link AppPolicyDTO#legacy()} 全通配策略，绝不因新授权面被拦。
 */
public final class OpenApiScopes {

    /** 通配 scope：授权全部 API（注册默认值，保证默认充足）。 */
    public static final String ALL = "*";

    private static final Map<String, String> API_SCOPE = new LinkedHashMap<>();

    static {
        // 八个冻结方法（v1 语义不变，仅新增授权维度）
        API_SCOPE.put("account.open", "account:open");
        API_SCOPE.put("account.balance", "account:read");
        API_SCOPE.put("account.transfer", "account:transfer");
        API_SCOPE.put("fund.topup", "fund:topup");
        API_SCOPE.put("fund.withdraw", "fund:withdraw");
        API_SCOPE.put("trade.create", "trade:write");
        API_SCOPE.put("trade.pay", "trade:pay");
        API_SCOPE.put("trade.query", "trade:read");
        // 既有 additive 方法
        API_SCOPE.put("account.topup", "account:topup");
        API_SCOPE.put("account.withdraw", "account:withdraw");
        API_SCOPE.put("trade.refund", "trade:refund");
        API_SCOPE.put("notify.query", "notify:read");
        API_SCOPE.put("notify.resend", "notify:write");
        // C-5 多端收银台 9 方法（additive；业务方自渲染收银台，payment-pro 不自建 C 端页面）
        API_SCOPE.put("cashier.methods", "cashier:read");
        API_SCOPE.put("cashier.code.create", "cashier:code");
        API_SCOPE.put("cashier.code.parse", "cashier:read");
        API_SCOPE.put("cashier.code.query", "cashier:read");
        API_SCOPE.put("cashier.scanPay", "cashier:pay");
        API_SCOPE.put("cashier.verify", "cashier:verify");
        API_SCOPE.put("cashier.payVerified", "cashier:pay");
        API_SCOPE.put("cashier.payPassword.set", "cashier:password");
        API_SCOPE.put("cashier.sms.send", "cashier:sms");
        // CH-6 签约代扣开放面 2 方法（P2-F 申请项，只调 SignFacade 既有能力）
        API_SCOPE.put("channel.sign", "channel:sign");
        API_SCOPE.put("channel.agent.pay", "channel:agentpay");
    }

    private OpenApiScopes() {
    }

    /** API → 所需 scope；未登记的 API 返回 null（不做 scope 校验，additive 友好）。 */
    public static String of(String api) {
        return api == null ? null : API_SCOPE.get(api);
    }

    public static Map<String, String> catalog() {
        return new LinkedHashMap<>(API_SCOPE);
    }

    public static List<String> all() {
        return API_SCOPE.values().stream().distinct().toList();
    }

    /** 授权判定：scopes 含 "*" 或含该 API 的 scope 即通过；API 未登记 scope 时放行。 */
    public static boolean allows(List<String> scopes, String api) {
        String required = of(api);
        if (required == null) {
            return true;
        }
        if (scopes == null || scopes.isEmpty()) {
            return false;
        }
        return scopes.contains(ALL) || scopes.contains(required);
    }
}