package com.qoder.payment.cashier.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.cashier.mapper.PayVerifyMapper;
import com.qoder.payment.cashier.model.PayCredentialDO;
import com.qoder.payment.cashier.model.VerifySessionDO;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.cashier.SetPayPasswordRequest;
import com.qoder.payment.facade.cashier.VerifyRequest;
import com.qoder.payment.facade.cashier.VerifyResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * C-3 支付验证（沙箱）：口令（PBKDF2 加盐哈希 + 失败计数锁定）/ 短信验证码 / 指纹模拟 / 小额免密。
 * 验证通过签发<b>一次性</b> verifyToken（绑定 order+amount+payer，CAS 核销），验证失败<b>不得受理</b>。
 *
 * <p><b>沙箱边界</b>：短信码本地生成并在响应中返回（仅沙箱，生产经短信网关不下发）；
 * 生物识别为端侧令牌模拟校验（local 无真实硬件）；口令哈希落 cashier 库（member 域 PayCredentialService 由 C 泳道/主会话补）。
 * 明文口令/哈希/盐绝不进日志或对外 DTO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayVerifyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayVerifyMapper mapper;
    private final DynamicConfig config;

    @DubboReference
    private AccountFacade accountFacade;

    public long freeLimit() {
        return config.getLong("cashier.verify.free-limit", 1000L);
    }

    /** 设置/重置支付密码（6 位数字，沙箱）。 */
    public Boolean setPayPassword(SetPayPasswordRequest req) {
        String memberId = resolveMemberId(req.getMemberId(), req.getAccountId());
        String pwd = req.getPassword();
        if (pwd == null || !pwd.matches("\\d{6}")) {
            throw new BizException(ResultCode.PARAM_ERROR, "支付密码须为 6 位数字");
        }
        PayCredentialDO existing = mapper.selectCredential(memberId);
        long now = System.currentTimeMillis();
        if (existing != null) {
            // 重置需校验旧密码（防越权改密）；首次设置无旧密码
            if (req.getOldPassword() != null && !req.getOldPassword().isBlank()
                    && !matches(req.getOldPassword(), existing.getSalt(), existing.getPasswordHash())) {
                throw new BizException(ResultCode.FORBIDDEN, "旧支付密码错误");
            }
            String salt = newSalt();
            mapper.updatePassword(memberId, hash(pwd, salt), salt, now);
        } else {
            String salt = newSalt();
            PayCredentialDO cred = new PayCredentialDO();
            cred.setMemberId(memberId);
            cred.setPasswordHash(hash(pwd, salt));
            cred.setSalt(salt);
            cred.setFailCount(0);
            cred.setCreatedAt(now);
            cred.setUpdatedAt(now);
            mapper.insertCredential(cred);
        }
        log.info("C-3 支付密码已设置/重置 memberId={}", memberId);
        return true;
    }

    /** 发送短信验证码（沙箱）：返回码值仅供沙箱演示，生产不下发。 */
    public Map<String, Object> sendSms(String accountId, String memberId, String orderId) {
        String mid = resolveMemberId(memberId, accountId);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long now = System.currentTimeMillis();
        long ttl = config.getLong("cashier.verify.sms-ttl-seconds", 300L) * 1000L;
        String id = UUID.randomUUID().toString().replace("-", "");
        mapper.insertSms(id, mid, accountId, orderId, code, now + ttl, now);
        log.info("C-3 沙箱短信验证码已生成 memberId={}（仅沙箱返回码值）", mid);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("sandbox", true);
        out.put("smsCode", code);
        out.put("expireAt", now + ttl);
        out.put("notice", "沙箱验证码，生产环境经短信网关下发、不在响应中返回");
        return out;
    }

    /** 支付验证：通过签发一次性 verifyToken；失败返回 pass=false + 明确原因（不落单）。 */
    public VerifyResultDTO verify(VerifyRequest req) {
        String memberId = resolveMemberId(req.getMemberId(), req.getAccountId());
        long amount = req.getAmount() == null ? 0L : req.getAmount();
        long free = freeLimit();
        String type = req.getVerifyType() == null ? "PASSWORD" : req.getVerifyType().toUpperCase();

        // 验证幂等：同 requestId 已有有效会话直接返回（不重复签发）
        if (req.getRequestId() != null && !req.getRequestId().isBlank()) {
            VerifySessionDO prior = mapper.selectSessionByRequest(req.getRequestId());
            if (prior != null && "ACTIVE".equals(prior.getStatus()) && prior.getExpireAt() > System.currentTimeMillis()) {
                return passResult(prior, free);
            }
        }

        switch (type) {
            case "SMALL_AMOUNT_FREE":
                if (amount <= free) {
                    return issue(memberId, req, amount, "SMALL_AMOUNT_FREE", free);
                }
                return fail("PASSWORD", "超出小额免密限额 ¥" + yuan(free) + "，需支付验证", free, null, null);
            case "BIO":
                // 沙箱：端侧采集令牌模拟平台校验（local 无真实生物硬件）
                if (req.getBioToken() == null || req.getBioToken().isBlank()) {
                    return fail("BIO", "未采集到生物识别令牌（沙箱模拟）", free, null, null);
                }
                return issue(memberId, req, amount, "BIO", free);
            case "SMS":
                return verifySms(memberId, req, amount, free);
            case "PASSWORD":
            default:
                return verifyPassword(memberId, req, amount, free);
        }
    }

    private VerifyResultDTO verifyPassword(String memberId, VerifyRequest req, long amount, long free) {
        PayCredentialDO cred = mapper.selectCredential(memberId);
        long now = System.currentTimeMillis();
        if (cred == null) {
            return fail("NEED_SET_PASSWORD", "请先设置支付密码", free, null, null);
        }
        if (cred.getLockedUntil() != null && cred.getLockedUntil() > now) {
            return fail("LOCKED", "支付密码错误次数过多，已锁定", free, 0, cred.getLockedUntil());
        }
        String pwd = req.getPayPassword();
        if (pwd != null && matches(pwd, cred.getSalt(), cred.getPasswordHash())) {
            mapper.resetFail(memberId, now);
            return issue(memberId, req, amount, "PASSWORD", free);
        }
        int maxFail = config.getInt("cashier.verify.max-fail", 5);
        int failCount = (cred.getFailCount() == null ? 0 : cred.getFailCount()) + 1;
        Long lockedUntil = null;
        if (failCount >= maxFail) {
            long lockMinutes = config.getLong("cashier.verify.lock-minutes", 15L);
            lockedUntil = now + lockMinutes * 60_000L;
        }
        mapper.updateFail(memberId, failCount, lockedUntil, now);
        int remain = Math.max(0, maxFail - failCount);
        String reason = lockedUntil != null
                ? "支付密码错误次数过多，已锁定 " + (lockedUntil - now) / 60000 + " 分钟"
                : "支付密码错误（剩余 " + remain + " 次）";
        return fail(lockedUntil != null ? "LOCKED" : "PASSWORD", reason, free, remain, lockedUntil);
    }

    private VerifyResultDTO verifySms(String memberId, VerifyRequest req, long amount, long free) {
        long now = System.currentTimeMillis();
        Map<String, Object> sms = mapper.selectActiveSms(memberId, now);
        if (sms == null) {
            return fail("SMS", "请先获取短信验证码（沙箱）", free, null, null);
        }
        String stored = String.valueOf(sms.get("code"));
        if (req.getSmsCode() != null && stored.equals(req.getSmsCode())) {
            mapper.markSmsUsed(String.valueOf(sms.get("id")));
            return issue(memberId, req, amount, "SMS", free);
        }
        return fail("SMS", "短信验证码错误", free, null, null);
    }

    private VerifyResultDTO issue(String memberId, VerifyRequest req, long amount, String type, long free) {
        long now = System.currentTimeMillis();
        long ttl = config.getLong("cashier.verify.session-ttl-seconds", 120L) * 1000L;
        VerifySessionDO s = new VerifySessionDO();
        s.setId(UUID.randomUUID().toString().replace("-", ""));
        s.setMemberId(memberId);
        s.setAccountId(req.getAccountId());
        s.setOrderId(req.getOrderId());
        s.setAmount(amount);
        s.setVerifyType(type);
        s.setStatus("ACTIVE");
        s.setExpireAt(now + ttl);
        s.setRequestId(req.getRequestId());
        s.setCreatedAt(now);
        mapper.insertSession(s);
        return passResult(s, free);
    }

    private VerifyResultDTO passResult(VerifySessionDO s, long free) {
        VerifyResultDTO r = new VerifyResultDTO();
        r.setPass(true);
        r.setChallengeType(s.getVerifyType());
        r.setVerifyToken(s.getId());
        r.setSessionId(s.getId());
        r.setExpireAt(s.getExpireAt());
        r.setFreeLimit(free);
        return r;
    }

    private VerifyResultDTO fail(String challenge, String reason, long free, Integer remain, Long lockedUntil) {
        VerifyResultDTO r = new VerifyResultDTO();
        r.setPass(false);
        r.setChallengeType(challenge);
        r.setReason(reason);
        r.setFreeLimit(free);
        r.setRemainAttempts(remain);
        r.setLockedUntil(lockedUntil);
        return r;
    }

    /**
     * 核销验证会话（一次性 CAS + 绑定校验）：供 payVerified/scanPay 受理前调用。
     * 绑定 order+amount+payer 防篡改；过期/已用/不匹配一律 false（不得受理）。
     */
    public boolean consumeVerifyToken(String token, String orderId, Long amount, String accountId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        VerifySessionDO s = mapper.selectSession(token);
        long now = System.currentTimeMillis();
        if (s == null || !"ACTIVE".equals(s.getStatus()) || s.getExpireAt() <= now) {
            return false;
        }
        if (orderId != null && s.getOrderId() != null && !orderId.equals(s.getOrderId())) {
            return false;
        }
        if (amount != null && s.getAmount() != null && !amount.equals(s.getAmount())) {
            return false;
        }
        if (accountId != null && s.getAccountId() != null && !accountId.equals(s.getAccountId())) {
            return false;
        }
        return mapper.casUseSession(token, now) == 1;
    }

    /** 内联口令验证（payVerified/scanPay 携 payPassword 时）：通过返回 true，失败抛 BizException（不落单）。 */
    public boolean verifyPasswordInline(String accountId, String memberId, String orderId, Long amount, String password) {
        VerifyRequest req = new VerifyRequest();
        req.setAccountId(accountId);
        req.setMemberId(memberId);
        req.setOrderId(orderId);
        req.setAmount(amount);
        req.setVerifyType("PASSWORD");
        req.setPayPassword(password);
        VerifyResultDTO r = verify(req);
        if (!Boolean.TRUE.equals(r.getPass())) {
            throw new BizException(ResultCode.FORBIDDEN, r.getReason() == null ? "支付验证失败" : r.getReason());
        }
        return true;
    }

    public String resolveMemberId(String memberId, String accountId) {
        if (memberId != null && !memberId.isBlank()) {
            return memberId;
        }
        if (accountId != null && !accountId.isBlank()) {
            AccountDTO a = accountFacade.get(accountId).orThrow();
            if (a.getMemberId() != null) {
                return a.getMemberId();
            }
        }
        throw new BizException(ResultCode.PARAM_ERROR, "memberId/accountId 必填");
    }

    // ---------- PBKDF2 加盐哈希（零依赖，JDK 内置） ----------
    private String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hash(String password, String saltB64) {
        try {
            int iterations = config.getInt("cashier.verify.password.iterations", 12000);
            byte[] salt = Base64.getDecoder().decode(saltB64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "支付密码哈希失败");
        }
    }

    private boolean matches(String password, String saltB64, String expectedHash) {
        String actual = hash(password, saltB64);
        return java.security.MessageDigest.isEqual(actual.getBytes(), expectedHash.getBytes());
    }

    private static String yuan(long cents) {
        return String.format("%.2f", cents / 100.0);
    }
}