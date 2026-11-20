package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** RC-3 渠道内外对账运行汇总（SINGLE 表，UNIQUE(channel_code,biz_date)）。 */
@Data
public class ChannelReconRunDO {

    private String id;
    private String channelCode;
    private Long bizDate;
    private String source;
    private Long billId;
    private Long internalCount;
    private Long externalCount;
    private Long matchedCount;
    private Long longCount;
    private Long shortCount;
    private Long amountCount;
    private Long statusCount;
    private Long feeCount;
    private Long internalTotal;
    private Long externalTotal;
    private Long netDiff;
    private Boolean pass;
    private Long disputeCount;
    private String feeNote;
    private String message;
    private Long createdAt;
    private Long updatedAt;
}