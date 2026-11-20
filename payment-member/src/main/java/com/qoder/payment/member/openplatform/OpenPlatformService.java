package com.qoder.payment.member.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.secret.SecretCipher;
import com.qoder.payment.common.secret.SecretProvider;
import com.qoder.payment.facade.openplatform.ApiBillDTO;
import com.qoder.payment.facade.openplatform.ApiCallDelta;
import com.qoder.payment.facade.openplatform.ApiQuotaDTO;
import com.qoder.payment.facade.openplatform.AppPolicyDTO;
import com.qoder.payment.facade.openplatform.OpenApiScopes;
import com.qoder.payment.facade.openplatform.OpenAppDTO;
import com.qoder.payment.facade.openplatform.OpenAppRegisterRequest;
import com.qoder.payment.facade.openplatform.OpenAppUpdateRequest;
import com.qoder.payment.member.dataobject.MemberDO;
import com.qoder.payment.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * G-4a 开放平台应用注册/授权/撤销 + 配额与调用计费账本。
 *
 * <p><b>红线</b>：
 * <ul>
 *   <li>只读写 openapi_app / openapi_call_log 两张<b>新表</b>，不 UPDATE/DELETE member 既有行；</li>
 *   <li>计费只计量，<b>不调用 accounting/payment/trade</b>，不产生分录/支付单/余额变动；</li>
 *   <li>未注册 appId（ride-pro appM1）→ {@link AppPolicyDTO#legacy}，新闸门全部空转；</li>
 *   <li>E-1：list/get 脱敏（appSecret=null，仅指纹）；沙箱明文只在注册响应返回一次，DB 存 AES-GCM 密文。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenPlatformService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 沙箱 appId 前缀（与生产 app* 命名空间彻底分离）。 */
    private static final String SANDBOX_PREFIX = "sbx";

    private final OpenAppMapper openAppMapper;
    private final ApiCallLogMapper callLogMapper;
    private final MemberMapper memberMapper;
    private final SecretProvider secretProvider;

    public static String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE);
    }

    // ---------- 注册 / 授权 ----------

    @Transactional
    public OpenAppDTO register(OpenAppRegisterRequest request) {
        boolean sandbox = Boolean.TRUE.equals(request.getSandbox());
        String name = request.getName();
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "name 必填且不超过 64 字");
        }
        String appId = request.getAppId() == null || request.getAppId().isBlank()
                ? null : request.getAppId().trim();
        String merchantId;
        String plainSecret = null;
        if (sandbox) {
            // 沙箱：独立 appId + 独立密钥；不创建生产会员、不碰生产账户
            appId = SANDBOX_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            merchantId = "SBXM" + appId.substring(SANDBOX_PREFIX.length());
            plainSecret = UUID.randomUUID().toString().replace("-", "");
        } else {
            if (appId == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "生产应用注册必须提供既有商户 appId");
            }
            MemberDO merchant = memberMapper.selectByAppId(appId);
            if (merchant == null) {
                throw new BizException(ResultCode.NOT_FOUND, "appId 不存在: " + appId);
            }
            merchantId = merchant.getId();
        }
        if (openAppMapper.selectByAppId(appId) != null) {
            throw new BizException(ResultCode.CONFLICT, "应用已注册: " + appId);
        }
        long now = System.currentTimeMillis();
        OpenAppDO app = new OpenAppDO();
        app.setAppId(appId);
        app.setMerchantId(merchantId);
        app.setName(name);
        app.setScopes(joinScopes(request.getScopes()));
        app.setRateLimit(positive(request.getRateLimit()));
        app.setDailyQuota(request.getDailyQuota() == null || request.getDailyQuota() < 0 ? 0L : request.getDailyQuota());
        app.setUnitPrice(request.getUnitPrice() == null || request.getUnitPrice() < 0 ? 0L : request.getUnitPrice());
        app.setSandbox(sandbox ? 1 : 0);
        app.setStatus("ACTIVE");
        app.setApiVersion(version(request.getApiVersion()));
        app.setSecretFingerprint(plainSecret == null ? null : SecretCipher.fingerprint(plainSecret));
        app.setSecretCipher(plainSecret == null ? null : SecretCipher.encrypt(masterKey(), plainSecret));
        app.setOperator(request.getOperator());
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        openAppMapper.insert(app);
        log.info("开放平台应用注册 appId={} sandbox={} scopes={} rateLimit={} dailyQuota={} unitPrice={}",
                appId, sandbox, app.getScopes(), app.getRateLimit(), app.getDailyQuota(), app.getUnitPrice());
        OpenAppDTO dto = toDTO(app);
        dto.setAppSecret(plainSecret);
        return dto;
    }

    public OpenAppDTO get(String appId) {
        return toDTO(require(appId));
    }

    public List<OpenAppDTO> list() {
        return openAppMapper.selectAll().stream().map(this::toDTO).toList();
    }

    @Transactional
    public OpenAppDTO update(OpenAppUpdateRequest request) {
        OpenAppDO app = require(request.getAppId());
        if (request.getName() != null && !request.getName().isBlank()) {
            app.setName(request.getName());
        }
        if (request.getScopes() != null) {
            app.setScopes(joinScopes(request.getScopes()));
        }
        if (request.getRateLimit() != null) {
            app.setRateLimit(positive(request.getRateLimit()));
        }
        if (request.getDailyQuota() != null) {
            app.setDailyQuota(Math.max(0L, request.getDailyQuota()));
        }
        if (request.getUnitPrice() != null) {
            app.setUnitPrice(Math.max(0L, request.getUnitPrice()));
        }
        if (request.getApiVersion() != null) {
            app.setApiVersion(version(request.getApiVersion()));
        }
        app.setOperator(request.getOperator());
        app.setUpdatedAt(System.currentTimeMillis());
        openAppMapper.updatePolicy(app);
        log.info("开放平台应用策略变更 appId={} scopes={} rateLimit={} dailyQuota={} unitPrice={}",
                app.getAppId(), app.getScopes(), app.getRateLimit(), app.getDailyQuota(), app.getUnitPrice());
        return toDTO(require(app.getAppId()));
    }

    @Transactional
    public OpenAppDTO revoke(String appId, String operator) {
        require(appId);
        long now = System.currentTimeMillis();
        openAppMapper.updateStatus(appId, "REVOKED", operator, now, now);
        log.warn("开放平台授权撤销 appId={} operator={}（网关策略缓存同步失效，即时生效）", appId, operator);
        return toDTO(require(appId));
    }

    @Transactional
    public OpenAppDTO activate(String appId, String operator) {
        require(appId);
        openAppMapper.updateStatus(appId, "ACTIVE", operator, System.currentTimeMillis(), null);
        log.info("开放平台授权恢复 appId={} operator={}", appId, operator);
        return toDTO(require(appId));
    }

    /** 网关内部策略解析：未注册 → legacy 全放行（历史商户零影响）。 */
    public AppPolicyDTO resolvePolicy(String appId) {
        if (appId == null || appId.isBlank()) {
            return AppPolicyDTO.legacy(appId);
        }
        OpenAppDO app = openAppMapper.selectByAppId(appId);
        if (app == null) {
            return AppPolicyDTO.legacy(appId);
        }
        AppPolicyDTO policy = new AppPolicyDTO();
        policy.setAppId(app.getAppId());
        policy.setRegistered(true);
        policy.setStatus(app.getStatus());
        policy.setScopes(splitScopes(app.getScopes()));
        policy.setRateLimit(app.getRateLimit() == null ? 0 : app.getRateLimit());
        policy.setDailyQuota(app.getDailyQuota() == null ? 0L : app.getDailyQuota());
        policy.setUnitPrice(app.getUnitPrice() == null ? 0L : app.getUnitPrice());
        policy.setSandbox(app.getSandbox() != null && app.getSandbox() == 1);
        policy.setApiVersion(app.getApiVersion());
        policy.setMerchantId(app.getMerchantId());
        if (policy.isSandbox() && app.getSecretCipher() != null) {
            policy.setSandboxSecret(SecretCipher.decrypt(masterKey(), app.getSecretCipher()));
        }
        return policy;
    }

    // ---------- 配额 / 计费 ----------

    public ApiQuotaDTO quota(String appId) {
        AppPolicyDTO policy = resolvePolicy(appId);
        String date = today();
        long used = callLogMapper.sumCalls(appId, date);
        long rejected = callLogMapper.sumRejects(appId, date);
        ApiQuotaDTO dto = new ApiQuotaDTO();
        dto.setAppId(appId);
        dto.setRegistered(policy.isRegistered());
        dto.setStatus(policy.getStatus());
        dto.setSandbox(policy.isSandbox());
        dto.setScopes(policy.getScopes());
        dto.setRateLimit(policy.getRateLimit());
        dto.setDailyQuota(policy.getDailyQuota());
        dto.setUnitPrice(policy.getUnitPrice());
        dto.setUsedToday(used);
        dto.setRejectedToday(rejected);
        dto.setBizDate(date);
        dto.setRemaining(policy.getDailyQuota() == null || policy.getDailyQuota() == 0
                ? null : Math.max(0L, policy.getDailyQuota() - used));
        return dto;
    }

    public List<ApiBillDTO> bill(String appId, String bizDate) {
        String date = bizDate == null || bizDate.isBlank() ? today() : bizDate;
        List<ApiCallLogDO> rows = appId == null || appId.isBlank()
                ? callLogMapper.selectByDate(date) : callLogMapper.selectByAppDate(appId, date);
        List<ApiBillDTO> bills = new ArrayList<>();
        for (ApiCallLogDO row : rows) {
            ApiBillDTO dto = new ApiBillDTO();
            dto.setAppId(row.getAppId());
            dto.setApi(row.getApi());
            dto.setBizDate(row.getBizDate());
            dto.setCallCount(row.getCallCount());
            dto.setRejectCount(row.getRejectCount());
            dto.setUnitPrice(row.getUnitPrice());
            dto.setBilledAmount(row.getBilledAmount());
            dto.setUpdatedAt(row.getUpdatedAt());
            bills.add(dto);
        }
        return bills;
    }

    /** 批量累加计费（网关异步上报）：单条失败不影响其它条目，绝不抛给调用方影响资金链路。 */
    @Transactional
    public boolean recordCalls(List<ApiCallDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        for (ApiCallDelta delta : deltas) {
            if (delta == null || delta.getAppId() == null || delta.getApi() == null) {
                continue;
            }
            String date = delta.getBizDate() == null || delta.getBizDate().isBlank() ? today() : delta.getBizDate();
            long calls = delta.getCalls() == null ? 0L : Math.max(0L, delta.getCalls());
            long rejects = delta.getRejects() == null ? 0L : Math.max(0L, delta.getRejects());
            long price = delta.getUnitPrice() == null ? 0L : Math.max(0L, delta.getUnitPrice());
            try {
                accumulate(delta.getAppId(), delta.getApi(), date, calls, rejects, price, now);
            } catch (Exception e) {
                log.warn("开放平台计费累加失败 appId={} api={}: {}", delta.getAppId(), delta.getApi(), e.toString());
            }
        }
        return true;
    }

    private void accumulate(String appId, String api, String date, long calls, long rejects, long price, long now) {
        int updated = callLogMapper.accumulate(appId, api, date, calls, rejects, price, now);
        if (updated > 0) {
            return;
        }
        ApiCallLogDO row = new ApiCallLogDO();
        row.setId("OPL" + callLogMapper.nextId());
        row.setAppId(appId);
        row.setApi(api);
        row.setBizDate(date);
        row.setCallCount(calls);
        row.setRejectCount(rejects);
        row.setUnitPrice(price);
        row.setBilledAmount(calls * price);
        row.setUpdatedAt(now);
        try {
            callLogMapper.insert(row);
        } catch (Exception dup) {
            // 并发首插冲突：回到累加分支（唯一键 uk_openapi_call 保证不重复行）
            callLogMapper.accumulate(appId, api, date, calls, rejects, price, now);
        }
    }

    // ---------- 内部 ----------

    private OpenAppDO require(String appId) {
        OpenAppDO app = appId == null ? null : openAppMapper.selectByAppId(appId);
        if (app == null) {
            throw new BizException(ResultCode.NOT_FOUND, "开放平台应用不存在: " + appId);
        }
        return app;
    }

    /** E-1 延续：出参永不带明文密钥。 */
    private OpenAppDTO toDTO(OpenAppDO app) {
        OpenAppDTO dto = new OpenAppDTO();
        dto.setAppId(app.getAppId());
        dto.setMerchantId(app.getMerchantId());
        dto.setName(app.getName());
        dto.setScopes(splitScopes(app.getScopes()));
        dto.setRateLimit(app.getRateLimit());
        dto.setDailyQuota(app.getDailyQuota());
        dto.setUnitPrice(app.getUnitPrice());
        dto.setSandbox(app.getSandbox() != null && app.getSandbox() == 1);
        dto.setStatus(app.getStatus());
        dto.setApiVersion(app.getApiVersion());
        dto.setSecretFingerprint(app.getSecretFingerprint());
        dto.setAppSecret(null);
        dto.setOperator(app.getOperator());
        dto.setCreatedAt(app.getCreatedAt());
        dto.setUpdatedAt(app.getUpdatedAt());
        dto.setRevokedAt(app.getRevokedAt());
        return dto;
    }

    private static String joinScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return OpenApiScopes.ALL;
        }
        List<String> cleaned = scopes.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        return cleaned.isEmpty() ? OpenApiScopes.ALL : String.join(",", cleaned);
    }

    private static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of(OpenApiScopes.ALL);
        }
        return Arrays.stream(scopes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static int positive(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static String version(String value) {
        return "v2".equalsIgnoreCase(value) ? "v2" : "v1";
    }

    private String masterKey() {
        String key = secretProvider.getSecret(SecretCipher.MASTER_KEY_REF);
        if (key == null || key.isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "密钥主密钥缺失 ref=" + SecretCipher.MASTER_KEY_REF);
        }
        return key;
    }
}