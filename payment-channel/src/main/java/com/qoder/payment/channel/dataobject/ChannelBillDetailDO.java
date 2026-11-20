package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-4 对账文件明细（channel_bill_detail，UNIQUE(bill_id,channel_order_no)）。 */
@Data
public class ChannelBillDetailDO {

    private Long id;
    private Long billId;
    private String channelOrderNo;
    private String bizNo;
    private String type;
    private Long amount;
    private String status;
    private String raw;
    private String matchResult;
    private Long disputeId;
    /** 本次解析是否存在于账单文件（1=在，0=历史行已被最新账单移除；不删行，只标记）。 */
    private Integer present;
}