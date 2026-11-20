package com.qoder.payment.channel.dataobject;

import lombok.Data;

@Data
public class ChannelTradeDO {

    private String bizNo;
    private String type;
    private String origBizNo;
    private String cardNo;
    private Long amount;
    private String status;
    private String reason;
    private Long createdAt;
    private String channelOrderNo;
    private Long submittedAt;
    private Long receiptAt;
    /** CH-2a 受理渠道（路由决策落痕；历史行为空）。 */
    private String channelCode;
}