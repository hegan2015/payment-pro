package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-2 会计科目（accounting_subject）。科目只是分类标签，不参与余额计算。 */
@Data
public class SubjectDO {

    private String subjectCode;
    private String name;
    private String type;
    private String direction;
    private String parentCode;
    private Integer subjectLevel;
    private String systemAccount;
    private Boolean systemFlag;
    private String status;
    private Long createdAt;
}