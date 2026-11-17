package com.qoder.payment.trade.facade;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.dtx.TccParticipantTemplate;
import com.qoder.payment.facade.tcc.TccBranchRequest;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.payment.facade.trade.TradeTccContext;
import com.qoder.payment.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 交易 TCC 参与者：PAY 预留订单 / REFUND 退款标记。 */
@DubboService(group = "trade")
@RequiredArgsConstructor
public class TradeTccFacadeImpl implements TccParticipantFacade {

    private final TccParticipantTemplate template;
    private final TradeService tradeService;

    @Override
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            TradeTccContext ctx = TccContextUtil.fromJson(request.getContext(), TradeTccContext.class);
            template.tryAction(request, () -> {
                if ("REFUND".equals(request.getAction())) {
                    tradeService.checkRefundable(ctx);
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
                if (!"REFUND".equals(request.getAction())) {
                    tradeService.release(ctx);
                }
            });
            return null;
        });
    }
}