package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-3 渠道滑动窗口统计（成功率/平均耗时）+ 熔断态。
 * local 内存窗口、docker 亦内存窗口（零依赖同构；指标同时经 MetricsSupport 暴露给 Prometheus）。
 */
@Data
public class ChannelMetricsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String channelCode;
    private Long successCount;
    private Long failCount;
    private Double successRate;
    private Long avgCostMs;
    private Long windowStart;
    private Long sampleCount;
    /** 本地失败率熔断是否打开（与 Sentinel 守卫并联，任一命中即绕行）。 */
    private boolean breakerOpen;
}