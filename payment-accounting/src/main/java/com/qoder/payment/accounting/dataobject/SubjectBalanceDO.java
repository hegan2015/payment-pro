package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-2/AC-4 科目余额表（subject_balance）：科目 + 会计日的发生额与期末余额。 */
@Data
public class SubjectBalanceDO {

    private String subjectCode;
    private Long bizDate;
    private Long openingBalance;
    private Long debitTotal;
    private Long creditTotal;
    private Long balance;
    private Integer entryCount;
    private Integer accountCount;
    private Long updatedAt;
}