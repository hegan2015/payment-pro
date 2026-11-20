package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** S-7 模拟银行存管侧独立事件（未达账/差错/费用），只影响账实核对的「实际」侧。 */
@Data
public class ReserveBankAdjustDO {

    private String id;
    private Long amount;
    private String reason;
    private Long createdAt;
    /** AC-5 渠道维度（additive；null 等价 MOCK_BANK，P0 口径不变）。 */
    private String channelCode;
}