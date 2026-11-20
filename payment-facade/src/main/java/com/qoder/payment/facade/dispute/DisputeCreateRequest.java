package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 差错登记请求（幂等键 (DISPUTE, source[:checkName][:bizDate]:bizNo)）。
 * RECON 来源由对账分类自动填充；NOTIFY 来源由死信接入填充；PAYMENT/RESERVE 供人工/跨泳道登记。
 */
@Data
public class DisputeCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String type;
    private String source;
    private String bizNo;
    private String paymentId;
    private Long amount;
    private String reason;
    /** 可选：覆盖默认修复策略（REVERSE_AND_REPOST/SUSPENSE/NOTIFY_REPLAY/MANUAL）。 */
    private String strategy;
    /** RECON 来源核对项名（幂等去重维度）。 */
    private String checkName;
    /** 对账业务日期 yyyyMMdd（幂等去重维度）。 */
    private String bizDate;
}