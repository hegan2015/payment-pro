package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-6 代扣单出参。
 *
 * <p>状态机：{@code INIT → PROCESSING → SUCCESS}（异步渠道）/ {@code INIT → SUCCESS}（同步渠道）；
 * 失败 {@code → FAILED}（可重试，attempts &lt; maxRetry）{@code → TERMINATED}（重试耗尽/协议失效/人工终止）。
 * <p>每次尝试对应一个既有支付单（{@code paymentId}），尝试幂等键 {@code deductNo:A<n>}；
 * SUCCESS 为资金终态，不可终止/不可重复扣款。
 */
@Data
public class SignDeductDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 扣款单号（同时作为交易单 outTradeNo，商户侧幂等）。 */
    private String deductNo;
    private String signNo;
    private String accountId;
    private String merchantId;
    /** 调用方幂等键（UNIQUE）。 */
    private String requestId;
    /** 本次尝试的支付幂等键（deductNo:A<n>，确定性）。 */
    private String attemptRequestId;
    private String orderId;
    private String paymentId;
    private Long amount;
    /** INIT / PROCESSING / SUCCESS / FAILED / TERMINATED。 */
    private String status;
    private Integer attempts;
    private Integer maxRetry;
    private Long nextRetryAt;
    private String failReason;
    /** 周期累计归属键（DAY=yyyyMMdd / MONTH=yyyyMM / NONE=ALL）。 */
    private String periodKey;
    private String channelCode;
    private Long createdAt;
    private Long updatedAt;
    private Long finishedAt;
    /** true=幂等命中（同 requestId 重复请求），未新建扣款单。 */
    private Boolean repeated;
}