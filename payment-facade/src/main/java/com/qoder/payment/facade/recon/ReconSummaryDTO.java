package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-4 对账汇总报表：按日 / 渠道 / 商户 / 差异类型四维聚合（全部 Java 归并，跨片安全）。
 */
@Data
public class ReconSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    private List<Row> byDay = new ArrayList<>();
    private List<Row> byChannel = new ArrayList<>();
    private List<Row> byMerchant = new ArrayList<>();
    private List<Row> byDiffType = new ArrayList<>();
    /** 差错单按类型分布（dispute_order，SINGLE 表 Java 归并）。 */
    private List<Row> disputeByType = new ArrayList<>();
    private long totalRuns;
    private long passedRuns;
    private long failedRuns;
    private long disputeCount;

    /** 通用聚合行：dim 维度名，key 维度值，runs/passed/failed 批次或报告计数，disputes 差错数，amount 涉及金额（分）。 */
    @Data
    public static class Row implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String dim;
        private String key;
        private long runs;
        private long passed;
        private long failed;
        private long disputes;
        private long amount;

        public static Row of(String dim, String key) {
            Row r = new Row();
            r.setDim(dim);
            r.setKey(key);
            return r;
        }
    }
}