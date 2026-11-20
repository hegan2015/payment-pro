package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AC-5 账实核对（账务余额 vs 渠道存管/银行侧）按会计日 + 多渠道汇总结果。
 *
 * <p>账面 book = SYS_CASH 复式记账余额（权威）；实际 actual = book + 银行侧独立事件
 * + 渠道账单（CH-4）逐笔金额差异归集。diff = book − actual；
 * diff&gt;0 长款（LONG）、diff&lt;0 短款（SHORT）。
 *
 * <p>差异分级：NONE(0) / WARN(|diff|&lt;warn 阈值) / ERROR(&lt;critical) / CRITICAL(≥critical)，
 * 阈值经 DynamicConfig 可调；非 NONE → ERROR 告警日志 + 指标 + reserve_check_log 留痕
 * + 差错单（DisputeFacade，source=RESERVE，幂等 by bizDate:channel）。
 *
 * <p>P0 模拟银行口径完整保留：默认渠道仅 MOCK_BANK 且无账单差异时，
 * 本结果与 {@code reserveCheck()} 逐值一致。
 */
@Data
public class ReserveReconDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    private Long bookBalance;
    private Long actualBalance;
    private Long diff;
    private Boolean pass;
    /** NONE / WARN / ERROR / CRITICAL。 */
    private String level;
    /** NONE / LONG（长款）/ SHORT（短款）。 */
    private String direction;
    private Long checkedAt;
    private List<ChannelDetail> details = new ArrayList<>();
    /** 实际侧口径来源说明（模拟银行 / CH-4 账单是否就绪）。 */
    private String actualSource;
    private Boolean channelBillReady;

    @Data
    public static class ChannelDetail implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String channelCode;
        private Long bookBalance;
        private Long actualBalance;
        private Long diff;
        private Boolean pass;
        private String level;
        private String direction;
        /** 模拟银行存管侧独立事件累计（reserve_bank_adjust）。 */
        private Long bankAdjust;
        /** CH-4 渠道账单逐笔金额差异归集（billAmount − localAmount）。 */
        private Long billDiffAdjust;
        private Long billCount;
        /** MOCK_BANK / CHANNEL_BILL / MOCK_BANK+CHANNEL_BILL。 */
        private String source;
        private String checkLogId;
        private String disputeNo;
        private String message;
    }
}