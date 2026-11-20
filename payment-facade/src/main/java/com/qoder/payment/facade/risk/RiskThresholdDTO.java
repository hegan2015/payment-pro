package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-4 分级阈值（按场景）。默认值宽松，保证正常流量（含 ride 镜像资金流）score=0 → PASS。
 *
 * <p>分级：score &lt; challenge → PASS；[challenge,limit) → CHALLENGE；
 * [limit,reject) → LIMIT（降额，超 {@link #limitAmount} 拒）；
 * [reject,review) → REJECT；≥ review → MANUAL_REVIEW（差错平台建单）。
 */
@Data
public class RiskThresholdDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String scene;
    private Integer challengeScore;
    private Integer limitScore;
    private Integer rejectScore;
    private Integer reviewScore;
    /** LIMIT 档降额后的单笔上限（分）。 */
    private Long limitAmount;
    private Integer version;
    private Long updatedAt;
}