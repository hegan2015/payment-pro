package com.qoder.payment.facade.channel;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 渠道网关：模拟银行渠道，TCC 预授权/扣款/撤销。对标支付宝渠道网关。 */
public interface ChannelFacade {

    Result<List<ChannelTradeDTO>> listTrades();
}