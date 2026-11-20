package com.qoder.payment.trade.facade;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.TccContextUtil;
import com.qoder.tcc.TccParticipantTemplate;
import com.qoder.tcc.TccBranchRequest;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 交易 TCC 参与者：PAY 预留订单 / REFUND 退款标记 / STAGE_PAY 分阶段 / MERGE_PAY 合并子订单（P2-H additive）。 */
@DubboService(group = "trade")
@RequiredArgsConstructor
public class TradeTccFacadeImpl implements TccParticipantFacade {

    private final TccParticipantTemplate template;
    private final TradeService tradeService;
    private final com.qoder.payment.trade.service.StagePayService stagePayService;
    private final com.qoder.payment.trade.service.MergePayService mergePayService;

    @Override
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            TradeTccContext ctx = TccContextUtil.fromJson(request.getContext(), TradeTccContext.class);
            template.tryAction(request, () -> {
                if ("REFUND".equals(request.getAction())) {
                    tradeService.checkRefundable(ctx);
                } else if ("STAGE_PAY".equals(request.getAction())) {
                    stagePayService.tryStage(ctx);
                } else if ("MERGE_PAY".equals(request.getAction())) {
                    mergePayService.tryMergeSub(ctx);
                } else {
                    tradeService.reserve(ctx);
                }
            });
            return null;
        });
    }

    @Override
    public Result<Void> confirm(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            TradeTccContext ctx = TccContextUtil.fromJson(request.getContext(), TradeTccContext.class);
            template.confirm(request, () -> {
                if ("REFUND".equals(request.getAction())) {
                    tradeService.refundMark(ctx.getOrderId(), ctx.getAmount(), ctx.getBizNo());
                } else if ("STAGE_PAY".equals(request.getAction())) {
                    stagePayService.confirmStage(ctx);
                } else if ("MERGE_PAY".equals(request.getAction())) {
                    mergePayService.confirmMergeSub(ctx);
                } else {
                    tradeService.markPaid(ctx);
                }
            });
            return null;
        });
    }

    @Override
    public Result<Void> cancel(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            TradeTccContext ctx = TccContextUtil.fromJson(request.getContext(), TradeTccContext.class);
            template.cancel(request, () -> {
                if ("STAGE_PAY".equals(request.getAction())) {
                    stagePayService.cancelStage(ctx);
                } else if ("MERGE_PAY".equals(request.getAction())) {
                    mergePayService.cancelMergeSub(ctx);
                } else if (!"REFUND".equals(request.getAction())) {
                    tradeService.release(ctx);
                }
            });
            return null;
        });
    }
}