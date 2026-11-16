package com.qoder.payment.facade.payment;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 支付核心：支付单 + TCC 协调跨域分布式事务，
 * 本地消息表（outbox）可靠投递清结算/通知，异步补偿调度兜底。对标支付宝支付核心。
 */
public interface PaymentFacade {

    Result<PayResultDTO> pay(PayRequest request);

    Result<RefundResultDTO> refund(String paymentId, RefundRequest request);

    Result<List<PaymentDTO>> listPayments();

    Result<PaymentDTO> getPayment(String paymentId);
}