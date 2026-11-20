package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** CH-6 授权额度视图（只读）：单笔/周期累计已用与剩余 + 有效期 + 扣款日命中判定。 */
@Data
public class SignQuotaDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String signNo;
    private String status;
    private Long singleLimit;
    private Long periodLimit;
    /** DAY / MONTH / NONE。 */
    private String periodType;
    /** 当前周期键（yyyyMMdd / yyyyMM / ALL）。 */
    private String periodKey;
    /** 本周期已占用额度（分）：SUCCESS + 在途（INIT/PROCESSING）合计，杜绝并发超扣。 */
    private Long periodUsed;
    /** 本周期剩余额度（分）；periodLimit 为空表示不限（返回 null）。 */
    private Long periodRemaining;
    /** 本周期扣款笔数（含在途）。 */
    private Integer periodCount;
    private Long effectiveAt;
    private Long expiredAt;
    /** 当前是否在有效期内。 */
    private Boolean inValidPeriod;
    private Integer deductDay;
    /** 今日（Asia/Shanghai）日号。 */
    private Integer today;
    /** 扣款日是否命中（deductDay 为空=true 任意日）。 */
    private Boolean deductDayMatch;
    private Integer maxRetry;
    private Long retryIntervalMs;
}