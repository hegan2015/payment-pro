package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-4/RC-5 对账进度（recon_progress 各片行 Java 归并，顶层不做 SQL SUM——
 * ShardingSphere 会把藏进函数的聚合当普通投影返回多行，I-5a/b 已踩坑）。
 *
 * <p>聚合口径：total/checked/disputeCount = 各片累加；passed/failed = 通过/失败的分片数；
 * percent = checked*100/total；status = 任一片 FAILED→FAILED，任一片 RUNNING→RUNNING，
 * 全部 SUCCESS→SUCCESS，否则 PENDING。
 */
@Data
public class ReconProgressDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    private String reconType;
    private long total;
    private long checked;
    /** 通过的分片数。 */
    private long passed;
    /** 失败的分片数。 */
    private long failed;
    private long disputeCount;
    private long percent;
    private String status;
    private List<Shard> shards = new ArrayList<>();

    @Data
    public static class Shard implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

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
}