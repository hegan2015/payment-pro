package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3b 积分账户（<b>虚拟权益独立账本</b>，单位=积分，不是金额）。
 *
 * <p><b>与资金账本的边界</b>：本账户只记积分数量，不参与复式记账、不影响 Σbalance=0；
 * 只有「积分抵扣成金额」那一刻（usePoints）才经 accounting facade 动真实资金
 * （D SYS_MARKETING / C 用户户），积分侧与资金侧由同一 bizNo 关联、各自幂等。
 */
@Data
public class PointsAccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    /** 积分总额（含冻结）。 */
    private Long balance;
    /** 冻结积分。 */
    private Long frozen;
    /** 可用积分 = balance − frozen。 */
    private Long available;
    /** 累计过期积分。 */
    private Long expired;
    /** 乐观锁版本（每次变动 +1）。 */
    private Long version;
    private Long createdAt;
    private Long updatedAt;
}