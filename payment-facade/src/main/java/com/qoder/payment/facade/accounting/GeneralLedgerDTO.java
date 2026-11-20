package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-4 总账行：科目 + 会计日维度的期初/发生额/期末（= 明细账按科目汇总）。
 * 与 {@link SubjectBalanceDTO} 同源（subject_balance），额外带科目目录信息便于联查。
 */
@Data
public class GeneralLedgerDTO implements Serializable {

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
    private Long closingBalance;
    private Integer entryCount;
    private Integer accountCount;
}