package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-2a 渠道 SPI 受理请求（PAY）：渠道无关的统一入参，由 ChannelService 从 TCC 上下文装配。
 *
 * <p>channelCode 为空表示「由路由择优」（CH-3）；非空表示商户/上游指定渠道（仍需可用性校验）。
 * 金额单位分（long），卡号明文仅在渠道域内流转，出网关一律脱敏（既有口径不变）。
 */
@Data
public class ChannelPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 渠道交易业务号（PAY=paymentId），幂等键 channel_trade.biz_no。 */
    private String bizNo;
    /** 指定渠道（可空=路由择优）。 */
    private String channelCode;
    private String cardNo;
    /** 卡 BIN（前 6 位，路由因子；可由 cardNo 推导）。 */
    private String cardBin;
    private Long amount;
    /** 渠道环境标识（SANDBOX/PROD），由 channel_config 决定，仅作留痕。 */
    private String env;
}