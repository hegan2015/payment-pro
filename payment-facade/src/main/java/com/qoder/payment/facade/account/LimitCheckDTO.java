package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** M-3 限额校验结果（供 payment/risk 资金路径接线；默认限额宽松不误拦 ride/smoke）。 */
@Data
public class LimitCheckDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean pass;
    private String level;
    /** SINGLE/BALANCE。 */
    private String kind;
    private Long limit;
    private Long amount;
    private String reason;
}