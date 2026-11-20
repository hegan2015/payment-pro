package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-2 账户→科目映射（account_subject_map）。系统户固定映射，客户账户默认 2001。 */
@Data
public class AccountSubjectMapDO {

    private String accountId;
    private String subjectCode;
    private Long createdAt;
}