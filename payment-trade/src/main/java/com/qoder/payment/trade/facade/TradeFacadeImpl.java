package com.qoder.payment.trade.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class TradeFacadeImpl implements TradeFacade {

    private final TradeService tradeService;

    @Override
    public Result<OrderDTO> createOrder(OrderCreateRequest request) {
        return FacadeSupport.execute(() -> tradeService.createOrder(request));
    }

    @Override
    public Result<List<OrderDTO>> listOrders() {
        return FacadeSupport.execute(tradeService::listOrders);
    }

    @Override
    public Result<OrderDTO> getOrder(String orderId) {
        return FacadeSupport.execute(() -> tradeService.getOrder(orderId));
    }

    @Override
    public Result<OrderDTO> closeOrder(String orderId) {
        return FacadeSupport.execute(() -> tradeService.closeOrder(orderId));
    }

    @Override
    public Result<OrderDTO> refundMark(String orderId, long amount, String bizNo) {
        return FacadeSupport.execute(() -> tradeService.refundMark(orderId, amount, bizNo));
    }
}