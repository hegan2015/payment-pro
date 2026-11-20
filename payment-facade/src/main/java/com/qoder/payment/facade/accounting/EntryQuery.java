package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 账务流水全局分页查询条件（跨账户）。所有筛选可选。 */
@Data
public class EntryQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private String bizNo;
    private String dir;
    private String keyword;
    /** AC-2 科目代码筛选（additive，可选；null 不过滤，既有口径不变）。 */
    private String subjectCode;
    /** AC-3 会计日筛选（additive，可选）。 */
    private Long bizDate;
    /** AC-4 created_at 毫秒区间（含头不含尾，additive，可选）。 */
    private Long dateFrom;
    private Long dateTo;
    private int page = 1;
    private int size = 20;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * size;
    }
}