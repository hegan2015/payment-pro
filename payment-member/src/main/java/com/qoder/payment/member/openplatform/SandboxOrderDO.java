package com.qoder.payment.member.openplatform;

import lombok.Data;

/** G-4a 沙箱交易单（openapi_sandbox_order，UNIQUE(app_id,out_trade_no) 幂等）。 */
@Data
public class SandboxOrderDO {

    private String id;
    private String appId;
    private String merchantId;
    private String outTradeNo;
    private String subject;
    private Long amount;
    private String status;
    private String paymentId;
    private Long createdAt;
    private Long paidAt;
}