package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-2a SPI 可插拔观测：注册表里的适配器实现（新增渠道只需实现 SPI + 注册，开闭原则）。 */
@Data
public class ChannelAdapterDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String channelCode;
    /** 适配器实现类简名（MockBankAdapter / SandboxBankAdapter / EchoChannelAdapter）。 */
    private String adapterClass;
    private String adapterType;
    private String env;
    /** 是否异步受理适配器（PROCESSING → CH-1 WAITING 路径）。 */
    private boolean asyncCapable;
    /** channel_config 状态（ENABLED/DISABLED/MAINTENANCE）；未配置=UNCONFIGURED。 */
    private String configStatus;
    /** 是否支持对账文件下载。 */
    private boolean billSupported;
}