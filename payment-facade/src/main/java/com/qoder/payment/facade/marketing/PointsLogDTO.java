package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 积分流水（只追加，不可篡改）。
 * type：EARN 赚取 / USE 消耗 / FREEZE 冻结 / UNFREEZE 解冻 / REFUND 退回 / EXPIRE 过期 / SETTLE 冻结转消耗。
 * 幂等键 {@code points_log} UNIQUE(biz_no, type)。
 */
@Data
public class PointsLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String memberId;
    /** 变动量（正=增加，负=减少；FREEZE/UNFREEZE 记冻结量，balance 不变）。 */
    private Long changeAmount;
    private Long balanceAfter;
    private Long frozenAfter;
    private String type;
    private String bizNo;
    /** 关联业务号（支付单/订单）。 */
    private String refId;
    /** 资金侧凭证号（仅 USE/REFUND/SETTLE 有值）。 */
    private String fundBizNo;
    /** 资金侧抵扣入账户（仅 USE/REFUND/SETTLE 有值；退回按原户反向，绝不改户）。 */
    private String accountId;
    /** 抵扣金额（分，仅 USE/REFUND/SETTLE）。 */
    private Long amount;
    private String operator;
    private Long createdAt;
}