package com.qoder.payment.cashier.facade;

import com.qoder.payment.cashier.service.CashierService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.cashier.CashierDTO;
import com.qoder.payment.facade.cashier.CashierFacade;
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
    public Result<PayResultDTO> pay(PayRequest request) {
        return FacadeSupport.execute(() -> cashierService.pay(request));
    }
}