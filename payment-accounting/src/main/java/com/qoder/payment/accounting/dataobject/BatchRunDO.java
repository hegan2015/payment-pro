package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/**
 * AC-3 批处理状态机留痕（accounting_batch_run）：RUNNING → SUCCESS / FAILED。
 * UNIQUE(batch_type,biz_date,shard_index,shard_total)；失败重跑只 UPDATE 本行 attempt+1，绝不 DELETE。
 */
@Data
public class BatchRunDO {

    private String id;
    private String batchType;
    private Long bizDate;
    private Integer shardIndex;
    private Integer shardTotal;
    private String status;
    private String step;
    private Integer attempt;
    private String error;
    private Long startedAt;
    private Long finishedAt;
}