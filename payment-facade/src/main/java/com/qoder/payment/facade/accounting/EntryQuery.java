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
    private int page = 1;
    private int size = 20;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * size;
    }
}