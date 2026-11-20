package com.qoder.payment.channel.dataobject;

import lombok.Data;

/** CH-6/X-4 授权与代扣审计（受理前拒绝也留痕，但不落任何资金/扣款悬挂单）。 */
@Data
public class SignAuditDO {

    private Long id;
    private String signNo;
    private String deductNo;
    private String action;
    private String operator;
    private String requestId;
    private Long amount;
    private String result;
    private String reason;
    private Long createdAt;
}