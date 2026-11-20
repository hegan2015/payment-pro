package com.qoder.payment.cashier.facade;

import com.qoder.payment.cashier.service.PayCodeService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.cashier.CreateCodeRequest;
import com.qoder.payment.facade.cashier.PayCodeDTO;
import com.qoder.payment.facade.cashier.PayCodeFacade;
import com.qoder.payment.facade.cashier.ScanPayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** C-2 付款码/扫码沙箱门面（被扫 + 主扫）。 */
@DubboService
@RequiredArgsConstructor
public class PayCodeFacadeImpl implements PayCodeFacade {

    private final PayCodeService payCodeService;

    @Override
    public Result<PayCodeDTO> createCode(CreateCodeRequest request) {
        return FacadeSupport.execute(() -> payCodeService.createCode(request));
    }

    @Override
    public Result<PayCodeDTO> parseCode(String code) {
        return FacadeSupport.execute(() -> payCodeService.parseCode(code));
    }

    @Override
    public Result<PayResultDTO> scanPay(ScanPayRequest request) {
        return FacadeSupport.execute(() -> payCodeService.scanPay(request));
    }
}