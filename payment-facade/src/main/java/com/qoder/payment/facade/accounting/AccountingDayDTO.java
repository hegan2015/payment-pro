package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-3 会计日（accounting_day）：OPEN → CUTTING → CLOSED。
 *
 * <p>日切只切换会计日标签，<b>不动任何余额</b>；{@code balanceSum} 切前切后必须一致（恒 0）。
 * 在途 TCC 凭证归属受理时快照的会计日（{@code accounting_day_snapshot}），日切不影响其收敛。
 */
@Data
public class AccountingDayDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    /** OPEN / CUTTING / CLOSED。 */
    private String status;
    private Long openedAt;
    private Long cutAt;
    private Long closedAt;
    /** 日切前余额快照（JSON：balanceSum/totalDebit/totalCredit/accounts/entries）。 */
    private String prevBalanceSnapshot;
    private Long balanceSum;
    private Long totalDebit;
    private Long totalCredit;
    private Integer accounts;
    private Integer entries;
    /** 日切后即时复核的余额总和（与快照一致 ⇒ 余额连续）。 */
    private Long afterBalanceSum;
    private Boolean continuous;
    /** 本次调用是否真正执行了日切（false=幂等命中/锁未获取）。 */
    private Boolean executed;
    private String message;
}