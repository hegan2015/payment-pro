package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3b 积分冻结/解冻请求（纯虚拟权益，不动资金）。幂等键 {@code (POINTS_FREEZE|POINTS_UNFREEZE, bizNo)}。 */
@Data
public class PointsFreezeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private Long points;
    private String bizNo;
    private String reason;
    private String operator;
}