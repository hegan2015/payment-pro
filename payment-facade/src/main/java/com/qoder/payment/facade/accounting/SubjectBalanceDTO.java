package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-2/AC-4 科目余额表行（subject_balance）：某科目某会计日的发生额与期末余额。
 *
 * <p>{@code balance} = 该科目下所有账户期末余额之和（= 贷方累计 − 借方累计，与 account_balance 同口径）；
 * {@code debitTotal}/{@code creditTotal} = 当日该科目借/贷发生额；
 * {@code openingBalance} = balance − (creditTotal − debitTotal)（期初）。
 * 全部明细科目（level=2）的 balance 之和恒为 0（封闭系统守恒）。
 */
@Data
public class SubjectBalanceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String subjectCode;
    private String subjectName;
    private String type;
    private String direction;
    private String parentCode;
    private Integer level;
    private Long bizDate;
    private Long openingBalance;
    private Long debitTotal;
    private Long creditTotal;
    private Long balance;
    private Integer entryCount;
    private Integer accountCount;
    private Long updatedAt;
}