package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** P-2 组合退款腿分摊（受理时落 refund_record.leg_alloc_json，完结监听据此累加腿已退）。 */
@Data
public class LegAllocDO {

    private String legId;
    private Long amount;
    private Long fee;
}