package com.qoder.payment.channel.facade;

import com.qoder.payment.channel.service.ChannelService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class ChannelFacadeImpl implements ChannelFacade {

    private final ChannelService channelService;

    @Override
    public Result<List<ChannelTradeDTO>> listTrades() {
        return FacadeSupport.execute(channelService::listTrades);
    }
}