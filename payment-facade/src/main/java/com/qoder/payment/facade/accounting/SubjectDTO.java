package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-2 会计科目（accounting_subject）。
 *
 * <p>方向约定与账务余额口径一致：{@code balance = 贷方累计 − 借方累计}。
 * 资产/费用类借增贷减（direction=D），负债/权益/收入类贷增借减（direction=C）。
 * 科目只是分录的<b>分类标签</b>，不参与余额计算——既有系统户余额与记账口径一字不变。
 */
@Data
public class SubjectDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String subjectCode;
    private String name;
    /** ASSET / LIABILITY / COMMON / EQUITY / INCOME / EXPENSE。 */
    private String type;
    /** D=借增贷减，C=贷增借减。 */
    private String direction;
    private String parentCode;
    /** 层级：1=类别根（只汇总不记账），2=明细科目（可挂账户）。 */
    private Integer level;
    /** 固定映射的系统户（SYS_ 前缀）；null 表示非系统户科目。 */
    private String systemAccount;
    /** 是否系统户科目（1/0）。 */
    private Boolean systemFlag;
    /** ENABLED / DISABLED。 */
    private String status;
    private Long createdAt;
}