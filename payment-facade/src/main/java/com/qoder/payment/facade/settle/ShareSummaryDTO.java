package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-3 分账守恒视图：{@code allocated = Σ SHARE.amount - Σ SHARE_BACK.amount}，
 * {@code diff = settleNet - allocated}，误差必须为 0 才算 balanced。
 */
@Data
public class ShareSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String shareId;
    private String paymentId;
    private String merchantId;
    /** 原支付结算单净额（守恒基准）。 */
    private Long settleNet;
    private Long shareAmount;
    private Long shareFee;
    private Long shareNet;
    private Long backAmount;
    private Long backNet;
    private Long allocated;
    private Long diff;
    private Boolean balanced;
    private Integer detailCount;
}