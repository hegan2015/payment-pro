package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-3 路由规则（channel_route_rule）：结构化条件（金额区间 + 卡 BIN）+ 权重 + 版本。 */
@Data
public class ChannelRouteRuleDO {

    private Long id;
    private Integer priority;
    private Long minAmount;
    private Long maxAmount;
    private String cardBin;
    private String channelCode;
    private Integer weight;
    private String status;
    private Integer version;
    private Long createdAt;
}