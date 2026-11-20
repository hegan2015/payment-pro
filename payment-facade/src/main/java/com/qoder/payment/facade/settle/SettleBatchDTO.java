package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-1 结算批次：一个商户一个业务日一批，UNIQUE(merchant_id,biz_date) + 幂等键
 * {@code (SETTLE_BATCH, merchantId:bizDate)} 保证重复跑批不重结。
 *
 * <p>状态机 PENDING（待结算）→ SETTLING（结算中）→ SETTLED（已结算）/ FAILED（失败可重跑）。
 * 净额恒等：{@code net = gross - refund - fee = Σ结算单净额}。
 */
@Data
public class SettleBatchDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    /** 业务日（Asia/Shanghai 当日 00:00 的 epoch millis）。 */
    private Long bizDate;
    /** 业务日文本 yyyy-MM-dd（只读展示）。 */
    private String bizDateText;
    private Integer cycleDays;
    private Long gross;
    private Long fee;
    private Long net;
    private Long refund;
    private Integer recordCount;
    private String status;
    private String failReason;
    private Integer shardIndex;
    private Integer shardTotal;
    private Long createdAt;
    private Long settledAt;
}