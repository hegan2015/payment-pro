package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** 渠道回执日志（CH-1）：UNIQUE(biz_no,status) 去重，终态回执只留一条，乱序/重复可审计。 */
@Data
public class ChannelReceiptLogDO {

    private Long id;
    private String bizNo;
    private String channelOrderNo;
    private String status;
    private String rawPayload;
    private Long createdAt;
}