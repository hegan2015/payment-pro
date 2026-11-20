package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** N-4 通知分页结果（记录已脱敏：不含任何密钥，签名值只留预览）。 */
@Data
public class NotifyPageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long total;
    private int page;
    private int size;
    private List<NotifyRecordDTO> records;
}