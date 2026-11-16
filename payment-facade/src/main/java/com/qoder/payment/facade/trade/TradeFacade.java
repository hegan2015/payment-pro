package com.qoder.payment.facade.trade;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 交易域：交易单生命周期，不触碰资金。对标支付宝交易核心。 */
public interface TradeFacade {

    Result<OrderDTO> createOrder(OrderCreateRequest request);

    Result<List<OrderDTO>> listOrders();

    Result<OrderDTO> getOrder(String orderId);

    Result<OrderDTO> closeOrder(String orderId);

    /** 退款标记。bizNo 幂等，由支付核心 outbox 可靠投递。 */
    Result<OrderDTO> refundMark(String orderId, long amount, String bizNo);
}