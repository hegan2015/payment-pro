package com.qoder.payment.member.convert;

import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.member.dataobject.MemberDO;
import org.springframework.beans.BeanUtils;

public final class MemberConverter {

    private MemberConverter() {
    }

    public static MemberDTO toDTO(MemberDO source) {
        MemberDTO dto = toDTOWithSecret(source);
        if (dto != null) {
            dto.setAppSecret(null);
            dto.setGraceSecrets(null);
            dto.setGraceDeadlines(null);
            dto.setCertPublicKey(null);
        }
        return dto;
    }

    /** 仅 create / getByAppId(内部验签) 使用；list/get 一律走 toDTO 脱敏（E-1）。 */
    public static MemberDTO toDTOWithSecret(MemberDO source) {
        if (source == null) {
            return null;
        }
        MemberDTO dto = new MemberDTO();
        BeanUtils.copyProperties(source, dto);
        return dto;
    }
}