package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 红冲请求：对原凭证全额反向冲正（差错纠正，区别于业务退款）。 */
@Data
public class ReverseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String origBizNo;
    private String reason;
    private String requestId;
}