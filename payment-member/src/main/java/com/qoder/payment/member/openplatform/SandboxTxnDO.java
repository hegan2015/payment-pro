package com.qoder.payment.member.openplatform;

import lombok.Data;

/** G-4a 沙箱资金单/支付单（openapi_sandbox_txn，UNIQUE(app_id,request_id) 幂等）。 */
@Data
public class SandboxTxnDO {

    private String id;
    private String appId;
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
}