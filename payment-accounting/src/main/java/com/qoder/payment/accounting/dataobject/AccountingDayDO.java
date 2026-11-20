package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-3 会计日（accounting_day）：OPEN → CUTTING → CLOSED，日切只切标签不动余额。 */
@Data
public class AccountingDayDO {

    private Long bizDate;
    private String status;
    private Long openedAt;
    private Long cutAt;
    private Long closedAt;
    private String prevBalanceSnapshot;
    private Long balanceSum;
    private Long totalDebit;
    private Long totalCredit;
    private Integer accounts;
    private Integer entries;
}