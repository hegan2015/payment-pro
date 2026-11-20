package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenApiScopes;
import com.qoder.payment.gateway.web.MerchantCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-4a API 授权 token（OAuth2 client_credentials）：<b>appId + appSecret → access_token</b>。
 *
 * <p>与 HMAC 的关系（红线）：token 只承载<b>授权范围</b>，<b>不替代签名</b>——
 * 八个冻结方法仍必须 HMAC/RSA2 验签、仍免 Bearer、canonical 一字未改。
 * 请求可选携带 {@code X-Access-Token} 头或 {@code access_token} 参数，网关据此做 scope 二次收敛。
 *
 * <p>密钥来源：沙箱应用 → openapi_app 密文解密（策略内部字段）；
 * 生产/历史商户 → member 域既有 {@code getByAppId} 内部验签链路（经 MerchantCache，与冻结链同源）。
 */
@Slf4j
@Service
public class OpenApiAuthService {

    @DubboReference
    private MemberFacade memberFacade;

    @Autowired
    private AppPolicyCache policyCache;

    @Autowired
    private MerchantCache merchantCache;

    @Autowired
    private OpenApiTokenStore tokenStore;

    /** 颁发 token。appSecret 错误 → A0401；应用已撤销 → A0403。 */
    public Map<String, Object> issueToken(String appId, String appSecret, List<String> requestedScopes) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "appId/appSecret 必填");
        }
        AppPolicyDTO policy = policyCache.resolve(appId);
        if (policy.isRegistered() && policy.isRevoked()) {
            throw new BizException(ResultCode.FORBIDDEN, "应用授权已撤销: " + appId);
        }
        String secret = resolveSecret(appId, policy);
        if (secret == null || !secret.equals(appSecret)) {
            log.warn("开放平台 token 颁发失败（密钥不匹配）appId={}", appId);
            throw new BizException(ResultCode.UNAUTHORIZED, "appId 或 appSecret 无效");
        }
        List<String> granted = intersect(policy.getScopes(), requestedScopes);
        String token = tokenStore.issue(appId, granted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", token);
        result.put("token_type", "Bearer");
        result.put("expires_in", tokenStore.ttlSeconds());
        result.put("scope", String.join(" ", granted));
        result.put("appId", appId);
        result.put("sandbox", policy.isSandbox());
        result.put("apiVersion", policy.getApiVersion() == null ? "v1" : policy.getApiVersion());
        log.info("开放平台 token 颁发 appId={} scopes={} sandbox={}", appId, granted, policy.isSandbox());
        return result;
    }

    /** 校验 token（无效 → A0401）；返回载荷。 */
    public OpenApiTokenStore.TokenEntry requireToken(String token) {
        OpenApiTokenStore.TokenEntry entry = tokenStore.validate(token);
        if (entry == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "access_token 无效或已过期");
        }
        return entry;
    }

    public void revokeByApp(String appId) {
        tokenStore.revokeByApp(appId);
    }

    /**
     * G-4a 注册应用<b>前置验签</b>（在配额计数之前，防伪造 appId 耗他人配额——与 I-7 把热点限流放在
     * 验签之后同一安全口径）。算法与冻结链逐字节一致：timestamp 毫秒窗 + HMAC(ACTIVE→宽限 RETIRED)/RSA2。
     *
     * <p><b>不消费 nonce</b>：nonce 仍由冻结链 {@code OpenApiController#verify} 消费，避免二次消费误判重放。
     * 本方法只对已注册应用调用；未注册（ride-pro）根本不走这里，冻结链行为一字不变。
     */
    public void verifySignatureForRegistered(String appId, Map<String, Object> body) {
        OpenApiSignature.timestamp(body);
        MemberDTO merchant = merchantCache.get(appId, () -> memberFacade.getByAppId(appId).orThrow());
        String sign = body.get("sign") == null ? null : body.get("sign").toString();
        String data = OpenApiSignature.canonical(body);
        if ("RSA2".equalsIgnoreCase(String.valueOf(body.get("sign_type")))) {
            if (merchant.getCertPublicKey() == null || merchant.getCertPublicKey().isBlank()) {
                throw new BizException("A0400", "RSA2 证书缺失，请先上传商户证书");
            }
            if (!OpenApiSignature.rsaVerify(merchant.getCertPublicKey(), data, sign)) {
                throw new BizException("A0400", "签名验证失败");
            }
            return;
        }
        boolean ok = merchant.getAppSecret() != null && OpenApiSignature.hmac(merchant.getAppSecret(), data).equals(sign);
        if (!ok && merchant.getGraceSecrets() != null) {
            long now = System.currentTimeMillis();
            List<String> graceSecrets = merchant.getGraceSecrets();
            List<Long> deadlines = merchant.getGraceDeadlines();
            for (int i = 0; i < graceSecrets.size(); i++) {
                if (deadlines != null && i < deadlines.size() && deadlines.get(i) != null && deadlines.get(i) <= now) {
                    continue;
                }
                String grace = graceSecrets.get(i);
                if (grace != null && OpenApiSignature.hmac(grace, data).equals(sign)) {
                    ok = true;
                    break;
                }
            }
        }
        if (!ok) {
            throw new BizException("A0400", "签名验证失败");
        }
    }

    private String resolveSecret(String appId, AppPolicyDTO policy) {
        if (policy.isSandbox()) {
            return policy.getSandboxSecret();
        }
        try {
            MemberDTO merchant = merchantCache.get(appId, () -> memberFacade.getByAppId(appId).orThrow());
            return merchant == null ? null : merchant.getAppSecret();
        } catch (Exception | LinkageError t) {
            log.warn("开放平台 token 密钥解析失败 appId={}: {}", appId, t.toString());
            return null;
        }
    }

    /** 授权范围收敛：应用 scopes ∩ 请求 scopes；应用为 "*" 时按请求（未请求=全部已登记 scope）。 */
    private static List<String> intersect(List<String> appScopes, List<String> requested) {
        List<String> app = appScopes == null || appScopes.isEmpty() ? List.of(OpenApiScopes.ALL) : appScopes;
        if (requested == null || requested.isEmpty()) {
            return app.contains(OpenApiScopes.ALL) ? OpenApiScopes.all() : app;
        }
        List<String> granted = new ArrayList<>();
        for (String scope : requested) {
            if (scope != null && !scope.isBlank() && (app.contains(OpenApiScopes.ALL) || app.contains(scope.trim()))) {
                granted.add(scope.trim());
            }
        }
        if (granted.isEmpty()) {
            throw new BizException(ResultCode.FORBIDDEN, "请求的 scope 均未授权");
        }
        return granted;
    }
}