package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * RC-3 渠道内外对账逐笔差异（channel_recon_diff，SINGLE 表，
 * UNIQUE(channel_code,biz_date,biz_no,diff_type) 重跑去重）。
 *
 * <p>差异分类：LONG 长款（外部账单有、内部流水无，渠道多扣）；SHORT 短款（内部有、账单无，渠道未扣/漏账）；
 * AMOUNT 金额不符；STATUS 状态不符；FEE 手续费不符。
 */
@Data
public class ChannelReconDiffDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String channelCode;
    /** 会计日 epoch millis。 */
    private Long bizDate;
    private String bizNo;
    private String channelOrderNo;
    /** LONG / SHORT / AMOUNT / STATUS / FEE。 */
    private String diffType;
    private Long internalAmount;
    private Long externalAmount;
    private Long internalFee;
    private Long externalFee;
    private String internalStatus;
    private String externalStatus;
    /** 关联差错单状态快照（OPEN/ASSIGNED/FIXING/RESOLVED/CLOSED/IGNORED）。 */
    private String status;
    private Long disputeId;
    /** 处置建议（长款挂账待退汇/短款挂账待补扣/金额状态人工核实）。 */
    private String suggestion;
    private Long createdAt;
    private Long updatedAt;
}