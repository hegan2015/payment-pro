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
}