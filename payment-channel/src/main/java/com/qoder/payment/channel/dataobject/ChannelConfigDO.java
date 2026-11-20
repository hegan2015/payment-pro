package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-2a/CH-5 渠道配置（channel_config）。 */
@Data
public class ChannelConfigDO {

    private String channelCode;
    private String name;
    private String adapterType;
    private String status;
    private String env;
    private Integer feeBps;
    private Integer weight;
    private Long singleLimit;
    private Long dailyLimit;
    private Long maintenanceStart;
    private Long maintenanceEnd;
    private String config;
    private Long createdAt;
    private Long updatedAt;
}