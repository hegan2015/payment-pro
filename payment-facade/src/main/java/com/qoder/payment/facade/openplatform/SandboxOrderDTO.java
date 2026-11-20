package com.qoder.payment.facade.openplatform;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** G-4a 沙箱交易单（形状对齐 v1 OrderDTO 的 ride 关键字段；(appId,outTradeNo) 幂等）。 */
@Data
public class SandboxOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String appId;
    private String merchantId;
    private String outTradeNo;
    private String subject;
    private Long amount;
    /** CREATED / SUCCESS。 */
    private String status;
    private String paymentId;
    private Long createdAt;
    private Long paidAt;
}