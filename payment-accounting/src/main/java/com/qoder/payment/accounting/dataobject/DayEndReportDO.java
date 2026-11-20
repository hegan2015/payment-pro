package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-3 日终报告（day_end_report）：损益汇总 + 结转状态（派生行，可幂等重算）。 */
@Data
public class DayEndReportDO {

    private Long bizDate;
    private Long incomeTotal;
    private Long expenseTotal;
    private Long profit;
    private Boolean transferred;
    private String transferBizNo;
    private Long debitTotal;
    private Long creditTotal;
    private Long balanceSum;
    private Integer subjectCount;
    private Integer entryCount;
    private Integer legacyEntryCount;
    private String status;
    private Long updatedAt;
}