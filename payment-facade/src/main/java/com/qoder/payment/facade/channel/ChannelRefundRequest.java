package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-2a 渠道 SPI 退款请求：origBizNo 为原扣款渠道交易号（必须已 CAPTURED 才可退）。 */
@Data
public class ChannelRefundRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 退款渠道交易业务号（REFUND=refundId）。 */
    private String bizNo;
    /** 原扣款业务号（paymentId）。 */
    private String origBizNo;
    private String channelCode;
    private String cardNo;
    private Long amount;
    private String env;
}