package com.qoder.payment.facade.settle;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 清结算域：结算单（商户净额）与手续费账单，T+0 演示结算。对标支付宝清结算系统。 */
public interface SettleFacade {

    /** paymentId 幂等。 */
    Result<SettleRecordDTO> recordPay(SettlePayRequest request);

    /** bizId 幂等。 */
    Result<SettleRecordDTO> recordRefund(SettleRefundRequest request);

    Result<List<SettleRecordDTO>> records(String merchantId);

    Result<SettleSummaryDTO> summary();
}