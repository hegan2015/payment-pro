package com.qoder.payment.recon.dataobject;

import lombok.Data;

/** RC-4/RC-5 分片进度（SINGLE 表，复合主键 biz_date+recon_type+shard_index+shard_total）。 */
@Data
public class ReconProgressDO {

    private Long bizDate;
    private String reconType;
    private Integer shardIndex;
    private Integer shardTotal;
    private Long total;
    private Long checked;
    private Integer passed;
    private Integer failed;
    private Long disputeCount;
    private String status;
    private Long updatedAt;
}