package com.qoder.payment.payment.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.payment.ChallengeContinueRequest;
import com.qoder.payment.facade.payment.FundPayRequest;
import com.qoder.payment.facade.payment.PayRequest;
import com.qoder.payment.facade.payment.PayResultDTO;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.payment.RefundDTO;
import com.qoder.payment.facade.payment.RefundRequest;
import com.qoder.payment.facade.payment.RefundResultDTO;
import com.qoder.payment.facade.payment.ShareBackRequest;
import com.qoder.payment.facade.payment.ShareOrderDTO;
import com.qoder.payment.facade.payment.ShareRequest;
import com.qoder.payment.payment.service.ChallengeService;
import com.qoder.payment.payment.service.PaymentCloseService;
import com.qoder.payment.payment.service.PaymentService;
import com.qoder.payment.payment.service.ProfitShareService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;
    private final ProfitShareService shareService;
    private final PaymentCloseService closeService;
    private final ChallengeService challengeService;
    /** P2-E P-7 营销抵扣编排（明细/回退/重分摊）。 */
    private final com.qoder.payment.payment.service.MarketingPayService marketingPayService;
    /** P2-H T-5/T-6（合并支付/分阶段/已支付改价）。 */
    private final com.qoder.payment.payment.service.MergePayOrchestrator mergePayOrchestrator;
    private final com.qoder.payment.payment.service.StagePayOrchestrator stagePayOrchestrator;
    private final com.qoder.payment.payment.service.PriceAdjustService priceAdjustService;

    @Override
    public Result<PayResultDTO> pay(PayRequest request) {
        return FacadeSupport.execute(() -> paymentService.pay(request));
    }

    @Override
    public Result<PayResultDTO> fundPay(FundPayRequest request) {
        return FacadeSupport.execute(() -> paymentService.fundPay(request));
    }

    @Override
    public Result<RefundResultDTO> refund(String paymentId, RefundRequest request) {
        return FacadeSupport.execute(() -> paymentService.refund(paymentId, request));
    }

    @Override
    public Result<List<PaymentDTO>> listPayments() {
        return FacadeSupport.execute(paymentService::listPayments);
    }

    @Override
    public Result<PaymentDTO> getPayment(String paymentId) {
        return FacadeSupport.execute(() -> paymentService.getPayment(paymentId));
    }

    /**
     * 手动补单收敛（S-5/P-1，CH-1 增量）。
     *
     * <p>注意：契约声明 {@code Result<PaymentDTO> reconverge(String paymentId)} 属
     * payment-facade 的 payment 子包（本次改动 ownership 之外），需由主会话在
     * {@code PaymentFacade} 补一行接口声明后，网关 {@code POST /api/payments/{id}/reconverge}
     * 即可经 Dubbo 调用本实现（实现与语义已就绪，幂等）。
     */
    public Result<PaymentDTO> reconverge(String paymentId) {
        return FacadeSupport.execute(() -> paymentService.reconverge(paymentId));
    }

    // ================= P1 · I 泳道 additive（既有方法签名/语义/幂等键一字未改） =================

    @Override
    public Result<ShareOrderDTO> share(ShareRequest request) {
        return FacadeSupport.execute(() -> shareService.share(request));
    }

    @Override
    public Result<ShareOrderDTO> shareBack(ShareBackRequest request) {
        return FacadeSupport.execute(() -> shareService.shareBack(request));
    }

    @Override
    public Result<ShareOrderDTO> getShare(String shareId) {
        return FacadeSupport.execute(() -> shareService.getShare(shareId));
    }

    @Override
    public Result<List<ShareOrderDTO>> listShares(String paymentId) {
        return FacadeSupport.execute(() -> shareService.listShares(paymentId));
    }

    @Override
    public Result<ShareOrderDTO> executeShare(String shareId) {
        return FacadeSupport.execute(() -> shareService.execute(shareId));
    }

    @Override
    public Result<RefundDTO> getRefund(String refundId) {
        return FacadeSupport.execute(() -> paymentService.getRefund(refundId));
    }

    @Override
    public Result<RefundDTO> refundReconverge(String refundId) {
        return FacadeSupport.execute(() -> paymentService.refundReconverge(refundId));
    }

    @Override
    public Result<PaymentDTO> close(String paymentId, String requestId) {
        return FacadeSupport.execute(() -> closeService.close(paymentId, requestId));
    }

    @Override
    public Result<PaymentDTO> cancel(String paymentId, String requestId) {
        return FacadeSupport.execute(() -> closeService.cancel(paymentId, requestId));
    }

    @Override
    public Result<PayResultDTO> continueChallenge(ChallengeContinueRequest request) {
        return FacadeSupport.execute(() -> challengeService.continueChallenge(request));
    }

    @Override
    public Result<PaymentDTO> repairCloseRace(String paymentId) {
        return FacadeSupport.execute(() -> closeService.repairCloseRace(paymentId));
    }

    @Override
    public Result<java.util.Map<String, Object>> repairTerminal(String paymentId, String reason, String requestId, String operator) {
        return FacadeSupport.execute(() -> closeService.repairTerminal(paymentId, reason, requestId, operator));
    }

    // ================= P2 · E 泳道 P-7 营销抵扣编排 additive（既有方法一字未改） =================

    @Override
    public Result<com.qoder.payment.facade.payment.MarketingDeductionDTO> getMarketingDeduction(String paymentId) {
        return FacadeSupport.execute(() -> marketingPayService.deduction(paymentId));
    }

    @Override
    public Result<com.qoder.payment.facade.payment.MarketingDeductionDTO> rollbackMarketing(String paymentId,
                                                                                          String requestId) {
        return FacadeSupport.execute(() -> marketingPayService.rollback(paymentId, requestId));
    }

    @Override
    public Result<com.qoder.payment.facade.payment.MarketingDeductionDTO> reallocateMarketing(String paymentId,
                                                                                            String requestId) {
        return FacadeSupport.execute(() -> marketingPayService.reallocate(paymentId, requestId));
    }

    // ================= P2 · H 泳道 T-5/T-6 additive（既有方法一字未改） =================

    @Override
    public Result<PayResultDTO> payMerge(com.qoder.payment.facade.payment.MergePayRequest request) {
        return FacadeSupport.execute(() -> mergePayOrchestrator.payMerge(request));
    }

    @Override
    public Result<PayResultDTO> payStage(com.qoder.payment.facade.payment.StagePayRequest request) {
        return FacadeSupport.execute(() -> stagePayOrchestrator.payStage(request));
    }

    @Override
    public Result<com.qoder.payment.facade.payment.AdjustPriceDTO> adjustPrice(
            com.qoder.payment.facade.payment.AdjustPriceRequest request) {
        return FacadeSupport.execute(() -> priceAdjustService.adjustPrice(request));
    }
}