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
}