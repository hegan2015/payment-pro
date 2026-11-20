package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 渠道 TCC 上下文：pay（预授权→扣款→撤销）/ refund（退款预检→执行→ noop）。 */
@Data
public class ChannelTccContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String origBizNo;
    private String cardNo;
    private Long amount;
    /**
     * CH-2a/CH-3 路由 key（additive，可空）：上游指定渠道时填，空则由渠道域 Router 择优。
     * 支付域未升级前恒为 null → 渠道域按默认策略选 MOCK_BANK，同步路径行为不变。
     */
    private String channelCode;
}