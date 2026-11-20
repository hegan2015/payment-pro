package com.qoder.payment.member.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.secret.SecretCipher;
import com.qoder.payment.common.secret.SecretProvider;
import com.qoder.payment.facade.member.SecretVersionDTO;
import com.qoder.payment.member.dataobject.MemberDO;
import com.qoder.payment.member.dataobject.SecretVersionDO;
import com.qoder.payment.member.mapper.MemberMapper;
import com.qoder.payment.member.mapper.SecretVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 商户密钥多版本（M-1）：明文只在 create/rotate 返回一次；DB 只存 AES-GCM 密文，
 * 主密钥经 {@link SecretProvider}（local 文件 600 / Vault KV v2 / KMS 可插拔）。
 * <ul>
 *   <li>轮换：新 ACTIVE + 旧 RETIRED（宽限期 graceSeconds 可配/可按次指定），宽限期内旧密钥仍可验签 → 不停机；</li>
 *   <li>历史明文 app_secret 列（wave1 前存量）惰性迁移为 v1 密文版本；迁移后以版本表为准，
 *       存量列保留不动（additive 数据红线），新密钥不再写该列。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretService {

    private final SecretVersionMapper secretVersionMapper;
    private final MemberMapper memberMapper;
    private final SecretProvider secretProvider;

    @Value("${payment.member.secret.grace-seconds:86400}")
    private long defaultGraceSeconds;

    /** create 时初始化 v1 ACTIVE（密文）。 */
    @Transactional
    public void initVersion(String memberId, String plainSecret) {
        insertVersion(memberId, 1, plainSecret);
    }

    /**
     * 解析当前 ACTIVE 密钥明文（内部验签链路专用）。
     * 无版本行且存量明文列有值 → 惰性迁移为 v1（存量列保留）。
     */
    @Transactional
    public String resolveActive(MemberDO member) {
        SecretVersionDO active = secretVersionMapper.selectActive(member.getId());
        if (active == null && member.getAppSecret() != null && !member.getAppSecret().isBlank()) {
            initVersion(member.getId(), member.getAppSecret());
            log.info("存量商户密钥惰性迁移为版本化密文 memberId={}", member.getId());
            active = secretVersionMapper.selectActive(member.getId());
        }
        if (active == null) {
            return member.getAppSecret();
        }
        return decrypt(active.getSecretCipher());
    }

    /** 宽限期内 RETIRED 密钥（多密钥并存验签兜底），版本倒序。 */
    public List<String> graceSecrets(String memberId) {
        return inGrace(memberId).stream().map(v -> decrypt(v.getSecretCipher())).toList();
    }

    /** 宽限期内 RETIRED 版本行（含 grace_until，供验签时点二次校验到期）。 */
    public List<SecretVersionDO> inGrace(String memberId) {
        return secretVersionMapper.selectInGrace(memberId, System.currentTimeMillis());
    }

    public Integer activeVersion(String memberId) {
        SecretVersionDO active = secretVersionMapper.selectActive(memberId);
        return active == null ? null : active.getVersion();
    }

    /** 轮换：旧 ACTIVE → RETIRED（保留宽限），新版本 ACTIVE；返回新明文（一次性）。 */
    @Transactional
    public String rotate(MemberDO member, Long graceSeconds) {
        if (!"MERCHANT".equals(member.getType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅商户支持密钥轮换");
        }
        // 确保存量密钥先落版本表，轮换后以版本表为准
        resolveActive(member);
        long grace = graceSeconds == null || graceSeconds < 0 ? defaultGraceSeconds : graceSeconds;
        long now = System.currentTimeMillis();
        String plain = UUID.randomUUID().toString().replace("-", "");
        secretVersionMapper.retireActive(member.getId(), now, now + grace * 1000);
        int version = secretVersionMapper.maxVersion(member.getId()) + 1;
        insertVersion(member.getId(), version, plain);
        log.info("商户密钥轮换 memberId={} version={} graceSeconds={}", member.getId(), version, grace);
        return plain;
    }

    /** 版本列表：仅版本/状态/指纹，绝不返回明文。 */
    public List<SecretVersionDTO> listVersions(String memberId) {
        return secretVersionMapper.selectByMember(memberId).stream().map(v -> {
            SecretVersionDTO dto = new SecretVersionDTO();
            dto.setMemberId(v.getMemberId());
            dto.setVersion(v.getVersion());
            dto.setStatus(v.getStatus());
            dto.setFingerprint(SecretCipher.fingerprint(decrypt(v.getSecretCipher())));
            dto.setCreatedAt(v.getCreatedAt());
            dto.setRetiredAt(v.getRetiredAt());
            dto.setGraceUntil(v.getGraceUntil());
            return dto;
        }).toList();
    }

    private void insertVersion(String memberId, int version, String plain) {
        SecretVersionDO row = new SecretVersionDO();
        row.setMemberId(memberId);
        row.setVersion(version);
        row.setSecretCipher(SecretCipher.encrypt(masterKey(), plain));
        row.setStatus("ACTIVE");
        row.setCreatedAt(System.currentTimeMillis());
        secretVersionMapper.insert(row);
    }

    private String decrypt(String cipher) {
        return SecretCipher.decrypt(masterKey(), cipher);
    }

    /** 供 MemberService 组装宽限密钥明文（内部验签链路专用）。 */
    public String decryptCipher(String cipher) {
        return decrypt(cipher);
    }

    private String masterKey() {
        String key = secretProvider.getSecret(SecretCipher.MASTER_KEY_REF);
        if (key == null || key.isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "密钥主密钥缺失 ref=" + SecretCipher.MASTER_KEY_REF);
        }
        return key;
    }
}