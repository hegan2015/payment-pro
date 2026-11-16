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
}