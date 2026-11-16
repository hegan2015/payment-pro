package com.qoder.payment.member.convert;

import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.member.dataobject.MemberDO;
import org.springframework.beans.BeanUtils;

public final class MemberConverter {

    private MemberConverter() {
    }

    public static MemberDTO toDTO(MemberDO source) {
        if (source == null) {
            return null;
        }
        MemberDTO dto = new MemberDTO();
        BeanUtils.copyProperties(source, dto);
        return dto;
    }
}