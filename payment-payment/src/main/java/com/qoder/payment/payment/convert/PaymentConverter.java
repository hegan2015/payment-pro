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
        // P2-E P-7 additive：净实付 = 订单全额 − 营销净抵扣（amount/fee 口径一字不改；无营销时 payAmount==amount）
        long amount = source.getAmount() == null ? 0L : source.getAmount();
        long marketing = source.getMarketingAmount() == null ? 0L : source.getMarketingAmount();
        dto.setMarketingAmount(marketing);
        dto.setMarketingStatus(source.getMarketingStatus() == null ? "NONE" : source.getMarketingStatus());
        dto.setPayAmount(amount - marketing);
        return dto;
    }

    /** 卡号脱敏：保留前 6 后 4，对外 DTO 不落全量 PAN。 */
    private static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 11) {
            return cardNo;
        }
        return cardNo.substring(0, 6) + "****" + cardNo.substring(cardNo.length() - 4);
    }

    /** additive：退款单 DO→DTO（管理台退款单列表页用）。 */
    public static com.qoder.payment.facade.payment.RefundDTO toDTO(com.qoder.payment.payment.dataobject.RefundDO s) {
        com.qoder.payment.facade.payment.RefundDTO d = new com.qoder.payment.facade.payment.RefundDTO();
        d.setId(s.getId());
        d.setPaymentId(s.getPaymentId());
        d.setAmount(s.getAmount());
        d.setFeeRefund(s.getFeeRefund());
        d.setStatus(s.getStatus());
        d.setRequestId(s.getRequestId());
        d.setBizNo(s.getBizNo());
        d.setChannelOrderNo(s.getChannelOrderNo());
        d.setFailReason(s.getFailReason());
        d.setReconcileCount(s.getReconcileCount());
        d.setCreatedAt(s.getCreatedAt());
        return d;
    }
}