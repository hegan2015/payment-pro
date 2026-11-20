package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** RC-2/RC-3 对账批次（SINGLE 表，UNIQUE(biz_date,recon_type,channel_code,shard_index,shard_total)）。 */
@Data
public class ReconBatchDO {

    private String id;
    private Long bizDate;
    private String reconType;
    private String channelCode;
    private Integer shardIndex;
    private Integer shardTotal;
    private String status;
    private Integer attempt;
    private String reportId;
    private Integer total;
    private Integer checked;
    private Integer passed;
    private Integer failed;
    private Integer disputeCount;
    private String message;
    private Long createdAt;
    private Long updatedAt;
}