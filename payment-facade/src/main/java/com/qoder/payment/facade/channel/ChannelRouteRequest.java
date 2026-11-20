package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-3 路由请求：amount 必填（限额硬过滤），channelCode 可空（择优），cardBin/cardNo 可空。 */
@Data
public class ChannelRouteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String channelCode;
    private Long amount;
    private String cardBin;
    private String cardNo;
    /** PAY / REFUND（默认 PAY）：退款路由沿用原扣款渠道，不做择优切换。 */
    private String type;
}