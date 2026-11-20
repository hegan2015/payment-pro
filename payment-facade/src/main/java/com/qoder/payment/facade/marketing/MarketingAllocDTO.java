package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** X-3b 分摊结果：来源维度 + 目标维度双清单，{@code diff} 恒为 0 才算守恒。 */
@Data
public class MarketingAllocDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String requestId;
    /** 营销抵扣总额（分）。 */
    private Long totalAmount;
    /** Σ目标维度分摊（USE−REFUND）。 */
    private Long allocated;
    /** Σ来源维度核销（USE−REFUND）。 */
    private Long sourceSum;
    /** allocated − totalAmount，守恒要求 0。 */
    private Long diff;
    /** sourceSum − totalAmount，守恒要求 0。 */
    private Long sourceDiff;
    private Boolean balanced;
    private List<AllocItemDTO> sources;
    private List<AllocItemDTO> targets;
    private Long createdAt;
}