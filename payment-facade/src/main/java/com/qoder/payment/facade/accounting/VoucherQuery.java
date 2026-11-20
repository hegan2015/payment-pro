package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-4 凭证联查条件（transfer_record 主表 + ledger_entry 分录）。所有筛选可选。
 *
 * <p>{@code subjectCode} 过滤经分录侧科目反查 bizNo（两步查询，跨片安全，不做跨库子查询）。
 * 凭证<b>只读</b>：本查询面不提供任何修改入口（VoucherGuard 红线）。
 */
@Data
public class VoucherQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String refId;
    private String subjectCode;
    private String accountId;
    private String voucherType;
    private Long bizDate;
    private Long dateFrom;
    private Long dateTo;
    private int page = 1;
    private int size = 20;
    /** 是否联查分录明细（默认 true）。 */
    private boolean withEntries = true;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * size;
    }
}