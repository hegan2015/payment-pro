package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 渠道终态回执（CH-1）：异步渠道回调/人工补录入口。
 *
 * <p>status 仅接受终态：PAY → CAPTURED/FAILED；REFUND → REFUNDED/FAILED。
 * 中间态（SUBMITTED/PREAUTH）回执被忽略，保证「终态先到、中间态后到」不覆盖终态。
 * 幂等键：bizNo + status（channel_receipt_log 唯一约束）+ channel_trade 状态 CAS。
 */
@Data
public class ChannelReceiptRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 渠道交易业务号（PAY=paymentId，REFUND=refundId），必填。 */
    private String bizNo;
    /** 渠道方订单号（可选，落 channel_trade.channel_order_no）。 */
    private String channelOrderNo;
    /** 终态：CAPTURED / REFUNDED / FAILED，必填。 */
    private String status;
    /** 回执金额（分，可选）：非空时必须与渠道交易金额一致，否则拒绝（资金护航）。 */
    private Long amount;
    /** 失败原因（可选）。 */
    private String reason;
    /** 渠道原始报文（可选，落回执日志留痕）。 */
    private String raw;
}