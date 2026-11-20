package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** N-4 批量重发结果：resent=实际重置进推送队列的条数，skipped=已送达/在途而跳过的条数。 */
@Data
public class NotifyBatchResendDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String requestId;
    private int requested;
    private int resent;
    private int skipped;
    private List<NotifyRecordDTO> records;
}