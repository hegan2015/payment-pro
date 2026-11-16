package com.qoder.payment.payment.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.payment.RefundResultDTO;
import com.qoder.payment.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;

    @Override
    public Result<PayResultDTO> pay(PayRequest request) {
        return FacadeSupport.execute(() -> paymentService.pay(request));
    }

    @Override
    public Result<RefundResultDTO> refund(String paymentId, RefundRequest request) {
        return FacadeSupport.execute(() -> paymentService.refund(paymentId, request));
    }

    @Override
    public Result<List<PaymentDTO>> listPayments() {
        return FacadeSupport.execute(paymentService::listPayments);
    }

    @Override
    public Result<PaymentDTO> getPayment(String paymentId) {
        return FacadeSupport.execute(() -> paymentService.getPayment(paymentId));
    }
}