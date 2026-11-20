package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * RC-2/RC-3 对账批次（recon_batch，SINGLE 表）：按会计日 + 类型 + 分片唯一，
 * 状态机 PENDING→RUNNING→SUCCESS/FAILED，同会计日重跑复用同一行（attempt 递增），不重复建单。
 */
@Data
public class ReconBatchDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    /** 会计日 epoch millis（Asia/Shanghai 当日 00:00）。 */
    private Long bizDate;
    /** DAILY / CHANNEL。 */
    private String reconType;
    /** CHANNEL 批次渠道码；DAILY 为空串。 */
    private String channelCode;
    private Integer shardIndex;
    private Integer shardTotal;
    /** PENDING / RUNNING / SUCCESS / FAILED。 */
    private String status;
    private Integer attempt;
    /** 关联 recon_report id（DAILY）或 channel_recon_run id（CHANNEL）。 */
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