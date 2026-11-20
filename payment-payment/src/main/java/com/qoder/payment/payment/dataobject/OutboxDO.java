package com.qoder.payment.payment.dataobject;

import lombok.Data;

@Data
public class OutboxDO {

    private Long id;
    private String event;
    private String bizId;
    private String payload;
    private String status;
    private Integer attempts;
    private Long nextRetryAt;
    private String lastError;
    private Long createdAt;
    /** I-4 路由 topic（additive 元数据；投递判定仍只认 event+biz_id+status，幂等键不变）。 */
    private String topic;
    /** I-4 半消息回执 msgId（普通消息/未发送为 null）。 */
    private String msgId;
    /** I-4 半消息判定留痕：COMMIT/ROLLBACK/UNKNOWN（null=未发送或崩溃未决）。 */
    private String halfState;
}