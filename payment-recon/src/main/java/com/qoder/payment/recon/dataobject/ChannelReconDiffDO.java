package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** RC-3 逐笔差异（SINGLE 表，UNIQUE(channel_code,biz_date,biz_no,diff_type)）。 */
@Data
public class ChannelReconDiffDO {

    private Long id;
    private String channelCode;
    private Long bizDate;
    private String bizNo;
    private String channelOrderNo;
    private String diffType;
    private Long internalAmount;
    private Long externalAmount;
    private Long internalFee;
    private Long externalFee;
    private String internalStatus;
    private String externalStatus;
    private String status;
    private Long disputeId;
    private String suggestion;
    private Long createdAt;
    private Long updatedAt;
}