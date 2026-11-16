package com.qoder.payment.settle.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.dtx.IdempotentTemplate;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.facade.settle.SettleSummaryDTO;
import com.qoder.payment.settle.dataobject.SettleRecordDO;
import com.qoder.payment.settle.mapper.SettleRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettleService {

    private final SettleRecordMapper settleRecordMapper;
    private final IdempotentTemplate idempotentTemplate;

    public SettleRecordDTO recordPay(SettlePayRequest request) {
        return idempotentTemplate.execute("SETTLE_PAY", request.getPaymentId(), SettleRecordDTO.class, () -> {
            SettleRecordDO record = new SettleRecordDO();
            record.setId("S" + settleRecordMapper.nextId());
            record.setType("PAY");
            record.setBizId(request.getPaymentId());
            record.setPaymentId(request.getPaymentId());
            record.setOrderId(request.getOrderId());
            record.setMerchantId(request.getMerchantId());
            record.setGross(request.getAmount());
            record.setFee(request.getFee());
            record.setNet(request.getAmount() - request.getFee());
            record.setMethod(request.getMethod());
            record.setStatus("SETTLED");
            record.setCreatedAt(System.currentTimeMillis());
            settleRecordMapper.insert(record);
            log.info("结算登记 paymentId={} gross={} fee={} net={}", record.getBizId(), record.getGross(), record.getFee(), record.getNet());
            return toDTO(record);
        });
    }

    public SettleRecordDTO recordRefund(SettleRefundRequest request) {
        return idempotentTemplate.execute("SETTLE_REFUND", request.getBizId(), SettleRecordDTO.class, () -> {
            SettleRecordDO pay = settleRecordMapper.selectByBiz(request.getPaymentId(), "PAY");
            if (pay == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "原支付结算单缺失: " + request.getPaymentId());
            }
            SettleRecordDO record = new SettleRecordDO();
            record.setId("S" + settleRecordMapper.nextId());
            record.setType("REFUND");
            record.setBizId(request.getBizId());
            record.setPaymentId(request.getPaymentId());
            record.setOrderId(pay.getOrderId());
            record.setMerchantId(pay.getMerchantId());
            record.setGross(request.getAmount());
            record.setFee(request.getFeeRefund());
            record.setNet(request.getAmount() - request.getFeeRefund());
            record.setMethod(pay.getMethod());
            record.setStatus("SETTLED");
            record.setCreatedAt(System.currentTimeMillis());
            settleRecordMapper.insert(record);
            log.info("退款结算登记 bizId={} paymentId={} amount={} feeRefund={}", record.getBizId(), request.getPaymentId(), request.getAmount(), request.getFeeRefund());
            return toDTO(record);
        });
    }

    public List<SettleRecordDTO> records(String merchantId) {
        List<SettleRecordDO> records = merchantId == null || merchantId.isBlank()
                ? settleRecordMapper.selectAll()
                : settleRecordMapper.selectByMerchant(merchantId);
        return records.stream().map(SettleService::toDTO).toList();
    }

    public SettleSummaryDTO summary() {
        SettleSummaryDTO dto = new SettleSummaryDTO();
        dto.setPayCount(settleRecordMapper.payCount());
        dto.setTotalGross(settleRecordMapper.totalGross());
        dto.setTotalFee(settleRecordMapper.totalFee());
        dto.setTotalNet(settleRecordMapper.totalNet());
        dto.setTotalRefund(settleRecordMapper.totalRefund());
        return dto;
    }

    private static SettleRecordDTO toDTO(SettleRecordDO source) {
        SettleRecordDTO dto = new SettleRecordDTO();
        BeanUtils.copyProperties(source, dto);
        return dto;
    }
}