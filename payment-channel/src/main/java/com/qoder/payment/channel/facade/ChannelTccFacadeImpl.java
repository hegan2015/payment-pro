package com.qoder.payment.channel.facade;

import com.qoder.payment.channel.service.ChannelService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.dtx.TccParticipantTemplate;
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.tcc.TccBranchRequest;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 渠道 TCC 参与者：预授权 / 扣款 / 撤销。 */
@DubboService(group = "channel")
@RequiredArgsConstructor
public class ChannelTccFacadeImpl implements TccParticipantFacade {

    private final TccParticipantTemplate template;
    private final ChannelService channelService;

    @Override
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            ChannelTccContext ctx = TccContextUtil.fromJson(request.getContext(), ChannelTccContext.class);
            template.tryAction(request, () -> channelService.preauth(ctx, request.getAction()));
            return null;
        });
    }

    @Override
    public Result<Void> confirm(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            ChannelTccContext ctx = TccContextUtil.fromJson(request.getContext(), ChannelTccContext.class);
            template.confirm(request, () -> channelService.capture(ctx, request.getAction()));
            return null;
        });
    }

    @Override
    public Result<Void> cancel(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            ChannelTccContext ctx = TccContextUtil.fromJson(request.getContext(), ChannelTccContext.class);
            template.cancel(request, () -> channelService.voidAuth(ctx));
            return null;
        });
    }
}