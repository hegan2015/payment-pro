package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * S-7 备付金账实核对结果：SYS_CASH 账务余额 vs 模拟银行存管实际余额。
 * diff = bookBalance - actualBalance；pass = (diff == 0)。差异→ERROR 告警+指标+留痕。
 */
@Data
public class ReserveCheckDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private Long bookBalance;
    private Long actualBalance;
    private Long diff;
    private Boolean pass;
    private Long checkedAt;
    /** AC-5 会计日维度（additive；P0 check() 路径为 null，口径不变）。 */
    private Long bizDate;
    /** AC-5 渠道维度（additive；P0 路径为 null，等价 MOCK_BANK）。 */
    private String channelCode;
    /** AC-5 差异分级 NONE/WARN/ERROR/CRITICAL（additive）。 */
    private String level;
    /** AC-5 长短款方向 NONE/LONG/SHORT（additive）。 */
    private String direction;
    /** AC-5 差异登记的差错单号（additive）。 */
    private String disputeNo;
}