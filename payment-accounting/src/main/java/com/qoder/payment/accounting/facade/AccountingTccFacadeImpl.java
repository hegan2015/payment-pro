package com.qoder.payment.accounting.facade;

import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.dtx.TccParticipantTemplate;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.tcc.TccBranchRequest;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 账务 TCC 参与者：Try 冻结 / Confirm 过账+解冻 / Cancel 解冻。 */
@DubboService(group = "accounting")
@RequiredArgsConstructor
public class AccountingTccFacadeImpl implements TccParticipantFacade {

    private final TccParticipantTemplate template;
    private final AccountingService accountingService;

    @Override
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            AccountingTccContext ctx = TccContextUtil.fromJson(request.getContext(), AccountingTccContext.class);
            template.tryAction(request, () -> accountingService.freeze(ctx));
            return null;
        });
    }

    @Override
    public Result<Void> confirm(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            AccountingTccContext ctx = TccContextUtil.fromJson(request.getContext(), AccountingTccContext.class);
            template.confirm(request, () -> accountingService.postAndUnfreeze(ctx));
            return null;
        });
    }

    @Override
    public Result<Void> cancel(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            AccountingTccContext ctx = TccContextUtil.fromJson(request.getContext(), AccountingTccContext.class);
            template.cancel(request, () -> accountingService.unfreeze(ctx));
            return null;
        });
    }
}