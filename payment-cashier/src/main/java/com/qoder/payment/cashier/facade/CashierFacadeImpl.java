package com.qoder.payment.cashier.facade;

import com.qoder.payment.cashier.service.CashierService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.cashier.CashierDTO;
import com.qoder.payment.facade.cashier.CashierFacade;
import com.qoder.payment.facade.cashier.SetPayPasswordRequest;
import com.qoder.payment.facade.cashier.VerifiedPayRequest;
import com.qoder.payment.facade.cashier.VerifyRequest;
import com.qoder.payment.facade.cashier.VerifyResultDTO;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
@RequiredArgsConstructor
public class CashierFacadeImpl implements CashierFacade {

    private final CashierService cashierService;

    @Override
    public Result<CashierDTO> cashier(String orderId) {
        return FacadeSupport.execute(() -> cashierService.cashier(orderId));
    }

    @Override
    public Result<CashierDTO> cashierForPayer(String orderId, String accountId) {
        return FacadeSupport.execute(() -> cashierService.cashierForPayer(orderId, accountId));
    }

    @Override
    public Result<PayResultDTO> pay(PayRequest request) {
        return FacadeSupport.execute(() -> cashierService.pay(request));
    }

    @Override
    public Result<PayResultDTO> payVerified(VerifiedPayRequest request) {
        return FacadeSupport.execute(() -> cashierService.payVerified(request));
    }

    @Override
    public Result<VerifyResultDTO> verify(VerifyRequest request) {
        return FacadeSupport.execute(() -> cashierService.verify(request));
    }

    @Override
    public Result<Boolean> setPayPassword(SetPayPasswordRequest request) {
        return FacadeSupport.execute(() -> cashierService.setPayPassword(request));
    }

    @Override
    public Result<java.util.Map<String, Object>> sendSms(String accountId, String memberId, String orderId) {
        return FacadeSupport.execute(() -> cashierService.sendSms(accountId, memberId, orderId));
    }
}