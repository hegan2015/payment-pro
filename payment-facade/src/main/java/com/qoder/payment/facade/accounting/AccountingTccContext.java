package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 账务 TCC 上下文。Try 阶段按 lines 中非系统户的借方金额冻结，
 * Confirm 阶段落复式分录并解冻，Cancel 阶段仅解冻。
 */
@Data
public class AccountingTccContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String refId;
    private String subject;
    private Boolean reversed;
    private List<TransferLineDTO> lines;
}