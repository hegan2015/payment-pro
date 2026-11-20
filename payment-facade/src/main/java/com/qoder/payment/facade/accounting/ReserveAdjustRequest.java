package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * S-7 模拟银行存管侧调整（P0 雏形）：模拟银行侧未达账/差错/费用等独立事件，
 * 只影响「实际余额」侧，绝不触碰账务凭证与余额。正数=银行侧多记（账<实），负数反之。
 * P1 AC-5 将由真实渠道账单/银行余额查询替换本模拟入口。
 */
@Data
public class ReserveAdjustRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 带符号调整额（分），非 0，绝对值 ≤ 1e12。 */
    private Long amount;
    private String reason;
    /** AC-5 渠道维度（additive，可选；null/空 = MOCK_BANK，P0 口径不变）。 */
    private String channelCode;
}