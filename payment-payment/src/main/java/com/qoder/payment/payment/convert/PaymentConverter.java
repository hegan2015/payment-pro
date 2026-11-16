package com.qoder.payment.payment.convert;

import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.payment.dataobject.PaymentDO;
import org.springframework.beans.BeanUtils;

public final class PaymentConverter {

    private PaymentConverter() {
    }

    public static PaymentDTO toDTO(PaymentDO source) {
        if (source == null) {
            return null;
        }
        PaymentDTO dto = new PaymentDTO();
        BeanUtils.copyProperties(source, dto);
        dto.setCardNo(maskCard(source.getCardNo()));
        return dto;
    }

    /** 卡号脱敏：保留前 6 后 4，对外 DTO 不落全量 PAN。 */
    private static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 11) {
            return cardNo;
        }
        return cardNo.substring(0, 6) + "****" + cardNo.substring(cardNo.length() - 4);
    }
}