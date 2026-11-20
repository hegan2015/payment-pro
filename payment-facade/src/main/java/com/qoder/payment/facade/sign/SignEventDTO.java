package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-6 事件事务 outbox 留痕（SIGN_SUCCESS / SIGN_CANCEL，N-3 已登记事件名）。
 *
 * <p>与协议状态变更<b>同一本地事务</b>落库（绝不丢事件），提交后经 {@code NotifyFacade.send} 投递；
 * notify 域 {@code event:bizId} 幂等吸收重复投递。投递不可达时留 PENDING/FAILED + lastError，
 * 由 {@code signEventDispatch} 作业重试（服务端兜底，不依赖前端补偿）。
 */
@Data
public class SignEventDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** SIGN_SUCCESS / SIGN_CANCEL。 */
    private String event;
    /** 事件业务键（=signNo），notify 幂等维度 event:bizId。 */
    private String bizId;
    private String signNo;
    private String deductNo;
    /** envelope payload JSON（含必填 agreementNo）。 */
    private String payload;
    /** PENDING / SENT / FAILED。 */
    private String status;
    private Integer attempts;
    /** notify 域通知单号（投递成功后回填）。 */
    private String notifyId;
    private String lastError;
    private Long createdAt;
    private Long sentAt;
    private Long nextRetryAt;
}