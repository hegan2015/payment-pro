package com.qoder.payment.member.auth;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberLoginRequest;
import com.qoder.payment.facade.member.MemberRegisterRequest;
import com.qoder.payment.facade.member.MemberSessionDTO;
import com.qoder.payment.facade.member.MemberTokenDTO;
import com.qoder.payment.member.dataobject.MemberDO;
import com.qoder.payment.member.dataobject.SmsCodeDO;
import com.qoder.payment.member.dataobject.UserCredentialDO;
import com.qoder.payment.member.mapper.MemberMapper;
import com.qoder.payment.member.mapper.SmsCodeMapper;
import com.qoder.payment.member.mapper.UserCredentialMapper;
import com.qoder.payment.member.service.AuditService;
import com.qoder.payment.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * M-5 C 端用户/商户登录认证（区别于 S-2/G-1 控制台认证，互不影响）：
 * <ul>
 *   <li>手机号+口令 / 短信验证码（沙箱）双模式；口令 BCrypt，明文不落库/不落日志/不落审计；</li>
 *   <li>会话外置：local 内存 / docker Redis（{@link MemberSessionStore}），TTL 过期、登出即失效；</li>
 *   <li>冻结会员（member.status=FROZEN）拒绝登录，冻结时既有会话全端失效；</li>
 *   <li>失败计数 5 次锁定 15 分钟（DB 计数，多实例共享）；</li>
 *   <li>改密校验旧口令 + 审计，成功后全部会话失效。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberAuthService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserCredentialMapper credentialMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final MemberMapper memberMapper;
    private final MemberService memberService;
    private final MemberSessionStore sessionStore;
    private final AuditService auditService;

    @Value("${payment.member.session.ttl-seconds:3600}")
    private long ttlSeconds;
    @Value("${payment.member.login.max-failures:5}")
    private int maxFailures;
    @Value("${payment.member.login.lock-minutes:15}")
    private int lockMinutes;
    @Value("${payment.member.sms.ttl-seconds:300}")
    private long smsTtlSeconds;
    @Value("${payment.member.sms.interval-seconds:60}")
    private long smsIntervalSeconds;

    @Transactional
    public MemberDTO register(MemberRegisterRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "name 必填");
        }
        if (!BankCardUtil.validMobile(request.getMobile())) {
            throw new BizException(ResultCode.PARAM_ERROR, "手机号格式不通过");
        }
        requireStrongPassword(request.getPassword());
        String mobileHash = BankCardUtil.sha256(request.getMobile());
        if (credentialMapper.selectByMobileHash(mobileHash) != null) {
            throw new BizException(ResultCode.CONFLICT, "手机号已注册");
        }
        MemberCreateRequest create = new MemberCreateRequest();
        create.setName(request.getName().trim());
        create.setType("USER");
        MemberDTO member = memberService.create(create);
        long now = System.currentTimeMillis();
        UserCredentialDO credential = new UserCredentialDO();
        credential.setMemberId(member.getId());
        credential.setMobileHash(mobileHash);
        credential.setMobileMask(BankCardUtil.maskMobile(request.getMobile()));
        credential.setPasswordHash(BCRYPT.encode(request.getPassword()));
        credential.setFailCount(0);
        credential.setLockedUntil(0L);
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        try {
            credentialMapper.insert(credential);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "手机号已注册");
        }
        audit("MEMBER_REGISTER", member.getId(), "200", "mobile=" + credential.getMobileMask());
        log.info("C 端注册 memberId={} mobile={}", member.getId(), credential.getMobileMask());
        return member;
    }

    /** 沙箱验证码：返回明文 code 仅供联调（真实短信通道属 N-x 后续）；60s 限频，5 分钟有效。 */
    public String sendSmsCode(String mobile) {
        if (!BankCardUtil.validMobile(mobile)) {
            throw new BizException(ResultCode.PARAM_ERROR, "手机号格式不通过");
        }
        String mobileHash = BankCardUtil.sha256(mobile);
        SmsCodeDO latest = smsCodeMapper.selectLatest(mobileHash);
        long now = System.currentTimeMillis();
        if (latest != null && now - latest.getCreatedAt() < smsIntervalSeconds * 1000) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        SmsCodeDO record = new SmsCodeDO();
        record.setId("SMS" + smsCodeMapper.nextId());
        record.setMobileHash(mobileHash);
        record.setCodeHash(BankCardUtil.sha256(code));
        record.setExpiresAt(now + smsTtlSeconds * 1000);
        record.setUsed(0);
        record.setCreatedAt(now);
        smsCodeMapper.insert(record);
        audit("SMS_SEND", mobileHash.substring(0, 16), "200", "mobile=" + BankCardUtil.maskMobile(mobile));
        return code;
    }

    public MemberTokenDTO login(MemberLoginRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "登录参数必填");
        }
        boolean smsMode = request.getSmsCode() != null && !request.getSmsCode().isBlank();
        UserCredentialDO credential;
        if (smsMode) {
            if (!BankCardUtil.validMobile(request.getMobile())) {
                throw new BizException(ResultCode.PARAM_ERROR, "手机号格式不通过");
            }
            credential = credentialMapper.selectByMobileHash(BankCardUtil.sha256(request.getMobile()));
            if (credential == null) {
                throw new BizException(ResultCode.NOT_FOUND, "手机号未注册");
            }
            requireActiveMember(credential.getMemberId());
            SmsCodeDO code = smsCodeMapper.selectLatestUnused(credential.getMobileHash());
            if (code == null || code.getExpiresAt() < System.currentTimeMillis()
                    || !code.getCodeHash().equals(BankCardUtil.sha256(request.getSmsCode().trim()))
                    || smsCodeMapper.markUsed(code.getId()) == 0) {
                audit("MEMBER_LOGIN_FAIL", credential.getMemberId(), "401", "sms 验证码错误或已过期");
                throw new BizException(ResultCode.UNAUTHORIZED, "验证码错误或已过期");
            }
        } else {
            String principal = request.getPrincipal();
            if (principal == null || principal.isBlank() || request.getPassword() == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "principal/password 必填");
            }
            principal = principal.trim();
            credential = BankCardUtil.validMobile(principal)
                    ? credentialMapper.selectByMobileHash(BankCardUtil.sha256(principal))
                    : credentialMapper.selectByMemberId(principal);
            if (credential == null) {
                throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
            }
            long now = System.currentTimeMillis();
            if (credential.getLockedUntil() != null && credential.getLockedUntil() > now) {
                audit("MEMBER_LOGIN_FAIL", credential.getMemberId(), "429", "锁定中");
                throw new BizException(ResultCode.TOO_MANY_REQUESTS, "登录失败次数过多，账户锁定中，请稍后再试");
            }
            requireActiveMember(credential.getMemberId());
            if (!BCRYPT.matches(request.getPassword(), credential.getPasswordHash())) {
                int fails = (credential.getFailCount() == null ? 0 : credential.getFailCount()) + 1;
                long lockedUntil = fails >= maxFailures ? now + lockMinutes * 60_000L : 0L;
                credentialMapper.updateFail(credential.getMemberId(), fails, lockedUntil, now);
                audit("MEMBER_LOGIN_FAIL", credential.getMemberId(), "401", "failCount=" + fails);
                throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
            }
            credentialMapper.updateFail(credential.getMemberId(), 0, 0L, now);
        }
        MemberDO member = memberMapper.selectById(credential.getMemberId());
        MemberTokenDTO token = issueToken(member, credential.getMobileMask());
        audit("MEMBER_LOGIN", member.getId(), "200", smsMode ? "mode=sms" : "mode=password");
        log.info("C 端登录 memberId={} mode={}", member.getId(), smsMode ? "sms" : "password");
        return token;
    }

    public Boolean logout(String token) {
        MemberSessionDTO session = sessionStore.get(token);
        sessionStore.delete(token);
        audit("MEMBER_LOGOUT", session == null ? null : session.getMemberId(), "200", null);
        return true;
    }

    public MemberSessionDTO getSession(String token) {
        MemberSessionDTO session = sessionStore.get(token);
        if (session == null) {
            MemberSessionDTO invalid = new MemberSessionDTO();
            invalid.setValid(false);
            return invalid;
        }
        session.setValid(true);
        return session;
    }

    @Transactional
    public Boolean changePassword(String memberId, String oldPassword, String newPassword, String operator) {
        UserCredentialDO credential = credentialMapper.selectByMemberId(memberId);
        if (credential == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员未设置登录凭证: " + memberId);
        }
        if (oldPassword == null || !BCRYPT.matches(oldPassword, credential.getPasswordHash())) {
            audit("MEMBER_PWD_CHANGE_FAIL", memberId, "401", "operator=" + operator + " 旧口令错误");
            throw new BizException(ResultCode.UNAUTHORIZED, "旧口令错误");
        }
        requireStrongPassword(newPassword);
        credential.setPasswordHash(BCRYPT.encode(newPassword));
        credential.setFailCount(0);
        credential.setLockedUntil(0L);
        credential.setUpdatedAt(System.currentTimeMillis());
        credentialMapper.update(credential);
        sessionStore.invalidateByMember(memberId);
        audit("MEMBER_PWD_CHANGE", memberId, "200", "operator=" + operator + " 全部会话已失效");
        log.info("C 端改密 memberId={} operator={}（旧口令校验通过，会话全端失效）", memberId, operator);
        return true;
    }

    // ------------------------------------------------------------------ internal

    private MemberTokenDTO issueToken(MemberDO member, String mobileMask) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        MemberSessionDTO session = new MemberSessionDTO();
        session.setMemberId(member.getId());
        session.setName(member.getName());
        session.setMobileMask(mobileMask);
        session.setValid(true);
        sessionStore.save(token, session, ttlSeconds);
        MemberTokenDTO dto = new MemberTokenDTO();
        dto.setToken(token);
        dto.setMemberId(member.getId());
        dto.setName(member.getName());
        dto.setExpiresIn(ttlSeconds);
        dto.setExpiresAt(session.getExpiresAt());
        return dto;
    }

    private void requireActiveMember(String memberId) {
        MemberDO member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员不存在: " + memberId);
        }
        if (!"ACTIVE".equals(member.getStatus())) {
            audit("MEMBER_LOGIN_FAIL", memberId, "403", "会员已冻结");
            throw new BizException(ResultCode.FORBIDDEN, "会员已冻结，禁止登录");
        }
    }

    private static void requireStrongPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "口令长度须 8-64 位");
        }
    }

    private void audit(String action, String bizNo, String result, String detail) {
        AuditWriteRequest audit = new AuditWriteRequest();
        audit.setUsername("member-auth");
        audit.setAction(action);
        audit.setBizNo(bizNo);
        audit.setResult(result);
        audit.setDetail(detail);
        auditService.write(audit);
    }
}