package com.qoder.payment.settle.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.facade.settle.SettleSummaryDTO;
import com.qoder.payment.settle.service.SettleService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class SettleFacadeImpl implements SettleFacade {

    private final SettleService settleService;

    @Override
    public Result<SettleRecordDTO> recordPay(SettlePayRequest request) {
        return FacadeSupport.execute(() -> settleService.recordPay(request));
    }

    @Override
    public Result<SettleRecordDTO> recordRefund(SettleRefundRequest request) {
        return FacadeSupport.execute(() -> settleService.recordRefund(request));
    }

    @Override
    public Result<List<SettleRecordDTO>> records(String merchantId) {
        return FacadeSupport.execute(() -> settleService.records(merchantId));
    }

    @Override
    public Result<SettleSummaryDTO> summary() {
        return FacadeSupport.execute(settleService::summary);
    }
}