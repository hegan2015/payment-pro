package com.qoder.payment.facade.sign;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-6 签约协议出参（脱敏：只含卡号掩码与哈希，<b>绝不含明文卡号/密文</b>）。
 *
 * <p>状态机：{@code SIGNED → CANCELLED}（解约）/ {@code SIGNED → EXPIRED}（到期作业置位）；
 * 终态不可逆，解约/到期后一律不可代扣。
 */
@Data
public class SignContractDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 平台协议号（幂等键 (SIGN,accountId:merchantId:channelCode) 的载体）。 */
    private String signNo;
    private String accountId;
    private String merchantId;
    private String channelCode;
    /** 渠道侧协议号（SPI sign 返回）。 */
    private String channelSignNo;
    /** **** **** **** 后四位。 */
    private String cardNoMask;
    /** SHA-256 hex（不可逆）。 */
    private String cardNoHash;
    /** SIGNED / CANCELLED / EXPIRED。 */
    private String status;
    private Long singleLimit;
    private Long periodLimit;
    /** DAY / MONTH / NONE。 */
    private String periodType;
    private Long effectiveAt;
    private Long expiredAt;
    private Integer deductDay;
    private Integer maxRetry;
    private Long retryIntervalMs;
    private String callbackUrl;
    private String appId;
    private String requestId;
    /** 授权范围留痕（JSON）。 */
    private String authorizedScope;
    private Long signedAt;
    private Long cancelledAt;
    private String cancelReason;
    private Long createdAt;
    private Long updatedAt;
    /** true=幂等命中（同 requestId 或同三元组已有有效协议），未重复签约。 */
    private Boolean repeated;
}