package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** X-3b 积分赚取请求（纯虚拟权益，不动资金）。幂等键 {@code (POINTS_EARN, bizNo)}。 */
@Data
public class PointsEarnRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    /** 积分数量（正整数）。 */
    private Long points;
    private String bizNo;
    private String refId;
    /** 该批积分绝对过期时间；缺省 validDays；两者皆空则用配置默认有效期。 */
    private Long expireAt;
    private Integer validDays;
    private String operator;
}