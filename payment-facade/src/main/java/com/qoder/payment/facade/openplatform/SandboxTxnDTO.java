package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * G-4a 沙箱资金单/支付单（形状对齐 v1 PaymentDTO 的 ride 关键字段：id/status/amount/fee/requestId）。
 * 沙箱单一律 fee=0、status=SUCCESS（同步受理，无渠道、无异步回执），只落 openapi_sandbox_txn。
 */
@Data
public class SandboxTxnDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String appId;
    /** TOPUP / WITHDRAW / TRANSFER / PAY。 */
    private String bizType;
    private String method;
    private String payerAccountId;
    private String toAccountId;
    private String orderId;
    private Long amount;
    private Long fee;
    private String status;
    private String requestId;
    private Long createdAt;
    /** 幂等复放标记（对齐 v1 PayResultDTO.repeated 语义）。 */
    private Boolean repeated;
}