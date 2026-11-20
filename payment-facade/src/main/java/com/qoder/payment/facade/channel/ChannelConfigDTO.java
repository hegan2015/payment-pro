package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-2a/CH-5 渠道配置（channel_config）：适配器类型/环境/状态/费率成本/限额/维护窗口/路由权重。 */
@Data
public class ChannelConfigDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String channelCode;
    private String name;
    /** 适配器实现标识（MOCK/SANDBOX/ECHO/...），与 SPI Bean 的 adapterType 对应。 */
    private String adapterType;
    /** ENABLED / DISABLED / MAINTENANCE。 */
    private String status;
    /** SANDBOX / PROD（沙箱与生产隔离标识，CH-2b）。 */
    private String env;
    /** 渠道成本费率（基点）：仅用于 CH-3 路由打分，不参与支付手续费计算（手续费口径不变）。 */
    private Integer feeBps;
    /** 路由权重（越大越优先，同分时的偏好因子）。 */
    private Integer weight;
    private Long singleLimit;
    private Long dailyLimit;
    private Long maintenanceStart;
    private Long maintenanceEnd;
    /** 适配器私有配置（JSON 文本，密钥只存 ref，明文走 SecretProvider）。 */
    private String config;
    private Long createdAt;
    private Long updatedAt;
}