package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ChannelTradeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String type;
    private String origBizNo;
    private String cardNo;
    private Long amount;
    /** PREAUTH（同步预授权）/ SUBMITTED（异步受理）/ CAPTURED / REFUNDED / VOIDED / FAILED。 */
    private String status;
    private String reason;
    private Long createdAt;
    /** 渠道方订单号（CH-1 增量，异步渠道回执携带）。 */
    private String channelOrderNo;
    /** 异步受理时间（毫秒，CH-1 增量；同步渠道为空）。 */
    private Long submittedAt;
    /** 终态回执到达时间（毫秒，CH-1 增量）。 */
    private Long receiptAt;
    /** 受理渠道（CH-2a 增量，路由决策落痕；历史行为空）。 */
    private String channelCode;
}