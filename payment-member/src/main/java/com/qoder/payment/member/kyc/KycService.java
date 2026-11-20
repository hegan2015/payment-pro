package com.qoder.payment.member.kyc;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.account.IdCardUtil;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.member.KycRequest;
import com.qoder.payment.facade.member.KycResultDTO;
import com.qoder.payment.member.dataobject.MemberKycDO;
import com.qoder.payment.member.mapper.KycMapper;
import com.qoder.payment.member.mapper.MemberMapper;
import com.qoder.payment.member.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-2 用户实名 KYC：四要素沙箱核验，一致 PASS（等级 II）联动 M-3 升级，不一致 FAIL 留痕可修正重提。
 * <ul>
 *   <li>幂等 (KYC,memberId)：PASS 后重复提交返回原结果；FAIL 允许修正重提（更新原行，审计逐次留痕）；</li>
 *   <li>敏感要素 SHA-256 哈希落库 + 掩码出参，明文不落库/不落日志/不落审计；</li>
 *   <li>开户 KYC 闸门经 DynamicConfig {@code account.kyc.required-for-open}（默认 false，
 *       保 ride account.open 懒开户红线；开启后 USER 开户需 KYC PASS）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycMapper kycMapper;
    private final MemberMapper memberMapper;
    private final KycVerifier kycVerifier;
    private final AuditService auditService;

    @Transactional
    public KycResultDTO submit(KycRequest request) {
        if (request == null || request.getMemberId() == null || request.getMemberId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "memberId 必填");
        }
        if (memberMapper.selectById(request.getMemberId()) == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员不存在: " + request.getMemberId());
        }
        MemberKycDO existing = kycMapper.selectByMemberId(request.getMemberId());
        if (existing != null && "PASS".equals(existing.getVerifyStatus())) {
            // 幂等：已实名重复提交返回原结果（不换绑要素）
            return toDTO(existing);
        }
        KycVerifier.VerifyResult result = kycVerifier.verify(
                request.getName(), request.getIdCardNo(), request.getBankCardNo(), request.getMobile());
        long now = System.currentTimeMillis();
        MemberKycDO kyc = existing == null ? new MemberKycDO() : existing;
        if (existing == null) {
            kyc.setId("K" + kycMapper.nextId());
            kyc.setMemberId(request.getMemberId());
            kyc.setCreatedAt(now);
        }
        kyc.setNameHash(hash(request.getName()));
        kyc.setNameMask(IdCardUtil.maskName(request.getName()));
        kyc.setIdCardNoHash(hash(request.getIdCardNo()));
        kyc.setIdCardMask(IdCardUtil.mask(request.getIdCardNo()));
        kyc.setBankCardNoHash(hash(request.getBankCardNo()));
        kyc.setBankCardMask(BankCardUtil.maskCard(request.getBankCardNo()));
        kyc.setMobileHash(hash(request.getMobile()));
        kyc.setMobileMask(BankCardUtil.maskMobile(request.getMobile()));
        kyc.setVerifyStatus(result.pass() ? "PASS" : "FAIL");
        kyc.setLevel(result.pass() ? "II" : null);
        kyc.setReason(result.reason());
        kyc.setVerifiedAt(now);
        kyc.setUpdatedAt(now);
        if (existing == null) {
            try {
                kycMapper.insert(kyc);
            } catch (DuplicateKeyException e) {
                // 并发提交竞争：读胜者行（PASS 幂等 / FAIL 已被对手更新）
                return toDTO(kycMapper.selectByMemberId(request.getMemberId()));
            }
        } else {
            kycMapper.updateByMemberId(kyc);
        }
        audit("KYC_SUBMIT", request.getMemberId(), kyc.getVerifyStatus(),
                "name=" + kyc.getNameMask() + " idCard=" + kyc.getIdCardMask()
                        + " card=" + kyc.getBankCardMask() + " mobile=" + kyc.getMobileMask()
                        + (result.reason() == null ? "" : " reason=" + result.reason()));
        log.info("KYC 核验 memberId={} status={} level={}", request.getMemberId(), kyc.getVerifyStatus(), kyc.getLevel());
        return toDTO(kyc);
    }

    public KycResultDTO get(String memberId) {
        MemberKycDO kyc = kycMapper.selectByMemberId(memberId);
        if (kyc == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员未提交 KYC: " + memberId);
        }
        return toDTO(kyc);
    }

    /** KYC 是否 PASS（M-3 升级 / 开户闸门用）。 */
    public boolean passed(String memberId) {
        MemberKycDO kyc = kycMapper.selectByMemberId(memberId);
        return kyc != null && "PASS".equals(kyc.getVerifyStatus());
    }

    private void audit(String action, String bizNo, String result, String detail) {
        AuditWriteRequest audit = new AuditWriteRequest();
        audit.setUsername("kyc-service");
        audit.setAction(action);
        audit.setBizNo(bizNo);
        audit.setResult(result);
        audit.setDetail(detail);
        auditService.write(audit);
    }

    private static String hash(String value) {
        return value == null ? null : BankCardUtil.sha256(value);
    }

    private static KycResultDTO toDTO(MemberKycDO kyc) {
        KycResultDTO dto = new KycResultDTO();
        dto.setMemberId(kyc.getMemberId());
        dto.setPass("PASS".equals(kyc.getVerifyStatus()));
        dto.setVerifyStatus(kyc.getVerifyStatus());
        dto.setLevel(kyc.getLevel());
        dto.setReason(kyc.getReason());
        dto.setNameMask(kyc.getNameMask());
        dto.setIdCardMask(kyc.getIdCardMask());
        dto.setBankCardMask(kyc.getBankCardMask());
        dto.setMobileMask(kyc.getMobileMask());
        dto.setVerifiedAt(kyc.getVerifiedAt());
        dto.setCreatedAt(kyc.getCreatedAt());
        return dto;
    }
}