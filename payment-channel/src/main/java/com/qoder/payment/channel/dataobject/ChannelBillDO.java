package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-4 渠道对账文件（channel_bill，UNIQUE(channel_code,biz_date)）。 */
@Data
public class ChannelBillDO {

    private Long id;
    private String channelCode;
    private String bizDate;
    private String filePath;
    private Long totalCount;
    private Long totalAmount;
    private String status;
    private Long parsedCount;
    private Long parsedAmount;
    private Long matchedCount;
    private Long diffCount;
    private Long disputeCount;
    private String message;
    private Long createdAt;
    private Long parsedAt;
}