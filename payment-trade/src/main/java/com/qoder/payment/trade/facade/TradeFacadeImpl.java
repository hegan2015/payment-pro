package com.qoder.payment.trade.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.facade.trade.TradeFacade;
import com.qoder.payment.facade.trade.TradeProductDTO;
import com.qoder.payment.trade.product.ProductRouter;
import com.qoder.payment.trade.service.EscrowService;
import com.qoder.payment.trade.service.OrderExpireService;
import com.qoder.payment.trade.service.PreAuthService;
import com.qoder.payment.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class TradeFacadeImpl implements TradeFacade {

    private final TradeService tradeService;
    private final ProductRouter productRouter;
    private final EscrowService escrowService;
    private final PreAuthService preAuthService;
    private final OrderExpireService orderExpireService;
    /** P2-H T-5/T-6（additive）。 */
    private final com.qoder.payment.trade.service.MergePayService mergePayService;
    private final com.qoder.payment.trade.service.StagePayService stagePayService;
    private final com.qoder.payment.trade.service.PriceChangeService priceChangeService;

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

    // ==================================================================
    // P1 · G 泳道 T-3/T-1/T-2/T-4（additive，上方既有方法一字未改）
    // ==================================================================

    @Override
    public Result<List<TradeProductDTO>> listProducts() {
        return FacadeSupport.execute(productRouter::catalog);
    }

    @Override
    public Result<OrderDTO> confirmReceive(String orderId) {
        return FacadeSupport.execute(() -> escrowService.confirmReceive(orderId));
    }

    @Override
    public Result<OrderDTO> preAuth(String orderId, String accountId, String requestId) {
        return FacadeSupport.execute(() -> preAuthService.preAuth(orderId, accountId, requestId));
    }

    @Override
    public Result<OrderDTO> completePreAuth(String orderId, long amount, String requestId) {
        return FacadeSupport.execute(() -> preAuthService.complete(orderId, amount, requestId));
    }

    @Override
    public Result<OrderDTO> cancelPreAuth(String orderId, String requestId) {
        return FacadeSupport.execute(() -> preAuthService.cancel(orderId, requestId));
    }

    @Override
    public Result<Integer> closeExpiredOrders(int shardIndex, int shardTotal) {
        return FacadeSupport.execute(() -> orderExpireService.closeExpired(shardIndex, shardTotal));
    }

    @Override
    public Result<Integer> autoConfirmEscrow(int shardIndex, int shardTotal) {
        return FacadeSupport.execute(() -> escrowService.autoConfirmDue(shardIndex, shardTotal));
    }

    // ==================================================================
    // P2 · H 泳道 T-5/T-6（additive，上方既有方法一字未改）
    // ==================================================================

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> createMergeOrder(
            com.qoder.payment.facade.trade.MergeCreateRequest request) {
        return FacadeSupport.execute(() -> mergePayService.createMergeOrder(request));
    }

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> getMergeOrder(String mergeId) {
        return FacadeSupport.execute(() -> mergePayService.getMergeOrder(mergeId));
    }

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> prepareMergePay(
            String mergeId, String paymentId, String carrierOrderId, java.util.List<Long> relFees) {
        return FacadeSupport.execute(() -> mergePayService.prepareMergePay(mergeId, paymentId, carrierOrderId, relFees));
    }

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> reopenMerge(String mergeId) {
        return FacadeSupport.execute(() -> mergePayService.reopenMerge(mergeId));
    }

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> dissolveMerge(String mergeId) {
        return FacadeSupport.execute(() -> mergePayService.dissolveMerge(mergeId));
    }

    @Override
    public Result<OrderDTO> createStagePlan(String orderId, java.util.List<Long> amounts, String requestId) {
        return FacadeSupport.execute(() -> stagePayService.createStagePlan(orderId, amounts, requestId));
    }

    @Override
    public Result<OrderDTO> changePrice(String orderId, long newAmount, String reason, String requestId) {
        return FacadeSupport.execute(() -> priceChangeService.changePrice(orderId, newAmount, reason, requestId));
    }

    @Override
    public Result<OrderDTO> markPriceChange(String orderId, long newAmount, String reason, String requestId,
                                            String direction, String refundId, String supplementOrderId) {
        return FacadeSupport.execute(() -> priceChangeService.markPriceChange(
                orderId, newAmount, reason, requestId, direction, refundId, supplementOrderId));
    }

    @Override
    public Result<java.util.List<com.qoder.payment.facade.trade.PriceChangeLogDTO>> listPriceLogs(String orderId) {
        return FacadeSupport.execute(() -> priceChangeService.listPriceLogs(orderId));
    }

    @Override
    public Result<com.qoder.payment.facade.trade.MergeOrderDTO> releaseMergeAfterCancel(String mergeId, String paymentId) {
        return FacadeSupport.execute(() -> mergePayService.releaseMergeAfterCancel(mergeId, paymentId));
    }

    @Override
    public Result<OrderDTO> releaseStageAfterCancel(String orderId, String paymentId) {
        return FacadeSupport.execute(() -> stagePayService.releaseStageAfterCancel(orderId, paymentId));
    }
}