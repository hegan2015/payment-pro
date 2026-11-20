package com.qoder.payment.facade.notify;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 消息通知域：商户异步通知，失败按退避重试（最多 5 次）。
 * 无 callbackUrl 时为 PULL 模式。对标支付宝商户通知系统。
 */
public interface NotifyFacade {

    Result<NotifyRecordDTO> send(NotifyRequest request);

    Result<List<NotifyRecordDTO>> records(String bizId);

    Result<NotifyRecordDTO> get(String notifyId);

    /**
     * N-2 死信重放：FAILED（重试耗尽）记录重置 PENDING/attempts=0 重新进入推送队列。
     * 幂等：非 FAILED 状态直接返回当前记录（不重复重置）；重放送达后差错单自动 RESOLVED。
     */
    Result<NotifyRecordDTO> replay(String notifyId);

    /**
     * N-4 分页查询（商户/事件/状态/时间窗）：结果<b>脱敏</b>——不含 appSecret/任何密钥，
     * 签名值只留预览、callbackUrl 去查询串。既有 {@link #records(String)} 口径不变。
     */
    Result<NotifyPageDTO> query(NotifyQueryRequest request);

    /**
     * N-4 单条重发（敏感操作，控制台侧需 step-up）：仅 FAILED/PULL 重置 PENDING 重推，
     * <b>DELIVERED/PENDING 一律跳过</b>（不重复推送已送达/在途通知），不产生新记录。
     * 幂等键 (NOTIFY_RESEND, notifyId:requestId)：同 requestId 重复调用返回首次结果，不再重置。
     */
    Result<NotifyRecordDTO> resend(String notifyId, String requestId);

    /**
     * N-4 批量重发：幂等键 (NOTIFY_RESEND_BATCH, requestId)，逐条 CAS 重置（同单条语义）。
     * requestId 必填，单批上限 100 条。
     */
    Result<NotifyBatchResendDTO> resendBatch(java.util.List<String> notifyIds, String requestId);

    /** N-3 事件类型目录（含冻结标记与 payload 模板必填字段），供控制台/文档展示。 */
    Result<java.util.List<java.util.Map<String, Object>>> events();
}