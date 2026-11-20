package com.qoder.payment.channel.dataobject;

import lombok.Data;

/**
 * CH-2b 沙箱银行「渠道侧」订单（sandbox_order）：模拟外部银行的自有账本，与平台 channel_trade 分离。
 *
 * <p>amount 为渠道侧实际金额（金额不符故障注入时与 requested_amount 不同），
 * status 为渠道侧终态，due_at 为回执应达时间（延迟注入），receipt_sent 标记回执是否已推送
 * （回执丢失注入时恒 0，平台只能靠查询补单收敛）。
 */
@Data
public class SandboxOrderDO {

    private Long id;
    private String channelOrderNo;
    private String bizNo;
    private String type;
    private Long amount;
    private Long requestedAmount;
    private String status;
    private String fault;
    private String bizDate;
    private Long dueAt;
    private Long createdAt;
    private Long finalizedAt;
    private Integer receiptSent;
    private Integer attempts;
}