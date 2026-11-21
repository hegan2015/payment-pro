package com.qoder.payment.marketing.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.marketing.MarketingTccContext;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.payment.marketing.service.MarketingTccService;
import com.qoder.tcc.TccBranchRequest;
import com.qoder.tcc.TccContextUtil;
import com.qoder.tcc.TccParticipantTemplate;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 营销 TCC 参与者（P-7 支付宝式）：Try 本地预占权益 / Confirm 核销+补贴直补商户+分摊 / Cancel 本地释放。
 * 经 {@link TccParticipantTemplate} 解决幂等/空回滚/防悬挂。Try 无远程资金，失败回滚干净；
 * 补贴在 Confirm（幂等可重试，崩溃恢复续驱必达）。营销 Try 在支付 begin 之后，崩溃恢复协调器统一驱动。
 */
@DubboService(group = "marketing")
@RequiredArgsConstructor
public class MarketingTccFacadeImpl implements TccParticipantFacade {

    private final TccParticipantTemplate template;
    private final MarketingTccService marketingTccService;

    @Override
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            MarketingTccContext ctx = TccContextUtil.fromJson(request.getContext(), MarketingTccContext.class);
            template.tryAction(request, () -> marketingTccService.reserveAll(ctx));
            return null;
        });
    }

    @Override
    public Result<Void> confirm(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            MarketingTccContext ctx = TccContextUtil.fromJson(request.getContext(), MarketingTccContext.class);
            template.confirm(request, () -> marketingTccService.commitAll(ctx));
            return null;
        });
    }

    @Override
    public Result<Void> cancel(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            MarketingTccContext ctx = TccContextUtil.fromJson(request.getContext(), MarketingTccContext.class);
            template.cancel(request, () -> marketingTccService.releaseAll(ctx));
            return null;
        });
    }
}