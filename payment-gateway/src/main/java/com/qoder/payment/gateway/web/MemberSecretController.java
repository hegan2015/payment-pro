package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.secret.SecretProvider;
import com.qoder.payment.common.secret.VaultSecretProvider;
import com.qoder.payment.facade.member.CertDTO;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商户密钥/证书管理（M-1/S-1，member:secret）：
 * 轮换（新密钥一次性返回，旧版宽限期并存验签）、版本列表（仅指纹）、证书上传/查询/到期告警。
 * 轮换/证书上传成功后清 OpenAPI 商户缓存（pp:openapi:app:*，60s TTL），避免验签用旧值。
 */
@RestController
public class MemberSecretController {

    @DubboReference
    private MemberFacade memberFacade;

    private final MerchantCache merchantCache;
    private final SecretProvider secretProvider;

    @Value("${payment.secret.vault.enabled:false}")
    private boolean vaultEnabled;

    public MemberSecretController(MerchantCache merchantCache, SecretProvider secretProvider) {
        this.merchantCache = merchantCache;
        this.secretProvider = secretProvider;
    }

    @PostMapping("/api/members/{id}/secrets/rotate")
    public ResponseEntity<Object> rotate(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Long graceSeconds = null;
        if (body != null && body.get("graceSeconds") instanceof Number n) {
            graceSeconds = n.longValue();
        }
        Result<MemberDTO> result = memberFacade.rotateSecret(id, graceSeconds);
        if (result.isSuccess() && result.getData() != null && result.getData().getAppId() != null) {
            merchantCache.evict(result.getData().getAppId());
        }
        return GatewaySupport.of(result);
    }

    @GetMapping("/api/members/{id}/secrets")
    public ResponseEntity<Object> versions(@PathVariable String id) {
        return GatewaySupport.of(memberFacade.listSecretVersions(id));
    }

    @PostMapping("/api/members/{id}/certs")
    public ResponseEntity<Object> uploadCert(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object pem = body.get("certPem");
        Result<CertDTO> result = memberFacade.uploadCert(id, pem == null ? null : pem.toString());
        if (result.isSuccess()) {
            Result<MemberDTO> member = memberFacade.get(id);
            if (member.isSuccess() && member.getData() != null && member.getData().getAppId() != null) {
                merchantCache.evict(member.getData().getAppId());
            }
        }
        return GatewaySupport.of(result);
    }

    @GetMapping("/api/members/{id}/certs")
    public ResponseEntity<Object> certs(@PathVariable String id) {
        return GatewaySupport.of(memberFacade.listCerts(id));
    }

    @GetMapping("/api/certs/expiring")
    public ResponseEntity<Object> expiring(@RequestParam(required = false) Integer days) {
        return GatewaySupport.of(memberFacade.expiringCerts(days));
    }

    @PostMapping("/api/certs/scan")
    public ResponseEntity<Object> scan(@RequestParam(required = false) Integer days) {
        return GatewaySupport.of(memberFacade.scanCerts(days));
    }

    /** SecretProvider 装配状态（S-1 收尾）：Vault 配置缺失自动降级 local 可在此观测。 */
    @GetMapping("/api/secrets/status")
    public ResponseEntity<Object> providerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", secretProvider.getClass().getSimpleName());
        status.put("vaultEnabled", vaultEnabled);
        status.put("degradedToLocal", secretProvider instanceof VaultSecretProvider vault && vault.degraded());
        return ResponseEntity.ok(status);
    }
}