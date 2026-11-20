package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-5 渠道限额/维护窗口视图：生效值（DynamicConfig 覆盖 DB）+ 当日累计 + 可用性判定。 */
@Data
public class ChannelLimitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String channelCode;
    private Long singleLimit;
    private Long dailyLimit;
    /** 当日（Asia/Shanghai yyyyMMdd）已用金额/笔数，跨日自然重置。 */
    private String bizDate;
    private Long usedAmount;
    private Long usedCount;
    private Long dailyRemaining;
    private Long maintenanceStart;
    private Long maintenanceEnd;
    private boolean inMaintenance;
    /** 综合可用性（状态 ENABLED + 非维护 + 已注册适配器）。 */
    private boolean available;
    private String reason;
    /** 生效值来源：dynamic-config / channel-config（动态下发证据）。 */
    private String limitSource;
    private String maintenanceSource;
}