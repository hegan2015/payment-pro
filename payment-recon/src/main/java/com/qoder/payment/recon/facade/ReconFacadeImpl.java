package com.qoder.payment.recon.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.recon.ReconFacade;
import com.qoder.payment.facade.recon.ReconReportDTO;
import com.qoder.payment.recon.service.ReconService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
@RequiredArgsConstructor
public class ReconFacadeImpl implements ReconFacade {

    private final ReconService reconService;

    @Override
    public Result<ReconReportDTO> run() {
        return FacadeSupport.execute(reconService::run);
    }

    @Override
    public Result<ReconReportDTO> latest() {
        return FacadeSupport.execute(reconService::latest);
    }
}