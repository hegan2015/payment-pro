package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-6 事件事务 outbox（SIGN_SUCCESS/SIGN_CANCEL），与协议状态变更同一本地事务落库。 */
@Data
public class SignEventDO {

    private Long id;
    private String event;
    private String bizId;
    private String signNo;
    private String deductNo;
    private String payload;
    /** PENDING / SENT / FAILED。 */
    private String status;
    private Integer attempts;
    private String notifyId;
    private String lastError;
    private Long createdAt;
    private Long sentAt;
    private Long nextRetryAt;
}