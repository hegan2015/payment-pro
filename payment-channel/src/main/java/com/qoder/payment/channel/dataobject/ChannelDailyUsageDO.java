package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-5 渠道日累计（channel_daily_usage，UNIQUE(channel_code,biz_date)，跨日自然重置）。 */
@Data
public class ChannelDailyUsageDO {

    private String channelCode;
    private String bizDate;
    private Long usedAmount;
    private Long usedCount;
}