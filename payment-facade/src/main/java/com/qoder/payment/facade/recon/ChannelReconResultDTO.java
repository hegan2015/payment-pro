package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-3 渠道内外对账结果（channel_recon_run，UNIQUE(channel_code,biz_date) 重跑复用同一行）。
 *
 * <p>外部口径来源 source：BILL=CH-4 对账文件（channel_bill_detail）；
 * QUERY_FALLBACK=CH-4 不可用时退化用 ChannelFacade.queryChannelSide/queryChannelTrade
 * 的渠道侧视图逐笔回查（模拟银行口径，如实标注，不冒充外部账单）。
 */
@Data
public class ChannelReconResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String channelCode;
    private Long bizDate;
    /** BILL / QUERY_FALLBACK。 */
    private String source;
    private Long billId;
    private long internalCount;
    private long externalCount;
    private long matchedCount;
    private long longCount;
    private long shortCount;
    private long amountCount;
    private long statusCount;
    private long feeCount;
    private long internalTotal;
    private long externalTotal;
    /** 外部总额 − 内部总额（分）。 */
    private long netDiff;
    private Boolean pass;
    private long disputeCount;
    private List<Long> disputeIds = new ArrayList<>();
    private List<ChannelReconDiffDTO> diffs = new ArrayList<>();
    /** 手续费口径说明（外部账单是否提供手续费列等，如实披露）。 */
    private String feeNote;
    private String message;
    private Long createdAt;
    private Long updatedAt;
}