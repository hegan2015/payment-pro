package com.qoder.payment.facade.risk;

import com.qoder.payment.common.result.Result;

/** 风控域：单笔限额 + 频率控制。对标支付宝风控系统（规则引擎简化版）。 */
public interface RiskFacade {

    Result<RiskResultDTO> check(RiskCheckRequest request);
}