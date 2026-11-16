package com.qoder.payment.facade.cashier;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;

/** 收银台：聚合交易单与可用支付方式，提交支付到支付核心。对标支付宝收银台。 */
public interface CashierFacade {

    Result<CashierDTO> cashier(String orderId);

    Result<PayResultDTO> pay(PayRequest request);
}