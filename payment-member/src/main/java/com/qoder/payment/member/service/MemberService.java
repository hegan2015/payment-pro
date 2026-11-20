package com.qoder.payment.member.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.member.convert.MemberConverter;
import com.qoder.payment.member.dataobject.MemberDO;
import com.qoder.payment.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;
    private final SecretService secretService;
    private final CertService certService;
    /** M-5：会员冻结时 C 端会话全端失效（控制台会话不受影响）。 */
    private final com.qoder.payment.member.auth.MemberSessionStore memberSessionStore;

    @Transactional
    public MemberDTO create(MemberCreateRequest request) {
        String name = request.getName();
        String type = request.getType();
        if (name == null || name.isBlank() || name.length() > 50) {
            throw new BizException(ResultCode.PARAM_ERROR, "名称必填且不超过 50 字");
        }
        if (!"USER".equals(type) && !"MERCHANT".equals(type)) {
            throw new BizException(ResultCode.PARAM_ERROR, "type 仅支持 USER 或 MERCHANT");
        }
        MemberDO member = new MemberDO();
        member.setId("M" + memberMapper.nextId());
        member.setName(name);
        member.setType(type);
        member.setStatus("ACTIVE");
        member.setCreatedAt(System.currentTimeMillis());
        String plainSecret = null;
        if ("MERCHANT".equals(type)) {
            member.setAppId("app" + member.getId());
            // M-1：新商户密钥只落版本表密文（secret_cipher），明文列不再写入；明文仅本次响应返回一次。
            plainSecret = UUID.randomUUID().toString().replace("-", "");
        }
        memberMapper.insert(member);
        if (plainSecret != null) {
            secretService.initVersion(member.getId(), plainSecret);
        }
        log.info("会员注册 memberId={} type={} name={}", member.getId(), type, name);
        MemberDTO dto = MemberConverter.toDTOWithSecret(member);
        dto.setAppSecret(plainSecret);
        dto.setSecretVersion(plainSecret == null ? null : 1);
        return dto;
    }

    public List<MemberDTO> list() {
        return memberMapper.selectAll().stream().map(MemberConverter::toDTO).toList();
    }

    public MemberDTO get(String memberId) {
        return MemberConverter.toDTO(require(memberId));
    }

    /** 内部验签链路（OpenAPI/notify）：返回 ACTIVE 密钥 + 宽限期 RETIRED 密钥 + RSA2 证书公钥。 */
    public MemberDTO getByAppId(String appId) {
        MemberDO member = memberMapper.selectByAppId(appId);
        if (member == null) {
            throw new BizException(ResultCode.NOT_FOUND, "appId 不存在: " + appId);
        }
        MemberDTO dto = MemberConverter.toDTOWithSecret(member);
        dto.setAppSecret(secretService.resolveActive(member));
        dto.setSecretVersion(secretService.activeVersion(member.getId()));
        var grace = secretService.inGrace(member.getId());
        dto.setGraceSecrets(grace.stream().map(v -> secretService.decryptCipher(v.getSecretCipher())).toList());
        dto.setGraceDeadlines(grace.stream().map(v -> v.getGraceUntil()).toList());
        dto.setCertPublicKey(certService.latestActivePublicKey(member.getId()));
        return dto;
    }

    public MemberDTO updateStatus(String memberId, String status) {
        if (!"ACTIVE".equals(status) && !"FROZEN".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ACTIVE 或 FROZEN");
        }
        require(memberId);
        memberMapper.updateStatus(memberId, status);
        if ("FROZEN".equals(status)) {
            memberSessionStore.invalidateByMember(memberId);
        }
        log.info("会员状态变更 memberId={} status={}", memberId, status);
        return MemberConverter.toDTO(require(memberId));
    }

    /** M-1 轮换：新密钥一次性返回；旧版 RETIRED 保留宽限期（宽限期内新旧均可验签）。 */
    public MemberDTO rotateSecret(String memberId, Long graceSeconds) {
        MemberDO member = require(memberId);
        String plain = secretService.rotate(member, graceSeconds);
        MemberDTO dto = MemberConverter.toDTO(member);
        dto.setAppSecret(plain);
        dto.setSecretVersion(secretService.activeVersion(memberId));
        return dto;
    }

    private MemberDO require(String memberId) {
        MemberDO member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员不存在: " + memberId);
        }
        return member;
    }
}