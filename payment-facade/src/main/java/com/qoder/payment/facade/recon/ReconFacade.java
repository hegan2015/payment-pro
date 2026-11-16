package com.qoder.payment.facade.recon;

import com.qoder.payment.common.result.Result;

/** 对账域：跨服务核对（账务试算平衡、支付-账务-交易-清结算四方一致）。对标支付宝对账中心。 */
public interface ReconFacade {

    Result<ReconReportDTO> run();

    Result<ReconReportDTO> latest();
}