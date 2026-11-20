package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-4 明细账查询条件（ledger_entry，按 account_id 分片）。所有筛选可选。
 *
 * <p>跨片分页沿用既有 {@code ORDER BY seq DESC LIMIT/OFFSET} 归并口径（不重不漏）；
 * {@code bizDate} 精确匹配会计日标签，{@code dateFrom/dateTo} 按 created_at 毫秒区间（含头不含尾）。
 */
@Data
public class LedgerQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private String subjectCode;
    private String bizNo;
    private String refId;
    private String dir;
    private Long bizDate;
    private Long dateFrom;
    private Long dateTo;
    /** additive：币种账套过滤。缺省 null = 全账套（与 trial.entries/全局流水分页同口径）；显式传值查该账套（CNY 含存量 NULL）。 */
    private String currency;
    private int page = 1;
    private int size = 20;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * size;
    }
}