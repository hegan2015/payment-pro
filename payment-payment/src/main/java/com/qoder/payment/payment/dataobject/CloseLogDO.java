package com.qoder.payment.payment.dataobject;

import lombok.Data;

/** P-5 关闭/撤销留痕（payment_close_log，UNIQUE(payment_id,type)，幂等审计）。 */
@Data
public class CloseLogDO {

    private String id;
    private String paymentId;
    /** CLOSE / CANCEL。 */
    private String type;
    private String requestId;
    private String bizNo;
    private Long createdAt;
}