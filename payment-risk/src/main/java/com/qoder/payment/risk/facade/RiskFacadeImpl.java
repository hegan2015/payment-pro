package com.qoder.payment.risk.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.facade.risk.RiskFacade;
import com.qoder.payment.facade.risk.RiskResultDTO;
import com.qoder.payment.risk.service.RiskService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
@RequiredArgsConstructor
public class RiskFacadeImpl implements RiskFacade {

    private final RiskService riskService;

    @Override
    public Result<RiskResultDTO> check(RiskCheckRequest request) {
        return FacadeSupport.execute(() -> riskService.check(request));
    }
}