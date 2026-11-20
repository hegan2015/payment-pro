package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-6 → A-5 资金敞口视图（销户护栏用，只读）：
 * 未释放保证金 + 未回收垫资 + 在途提现单，任一非零即禁止销户。
 */
@Data
public class FundExposureDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String accountId;
    /** 未释放未扣划保证金（分）。 */
    private Long depositHeld;
    /** 未回收垫资（分）。 */
    private Long advanceOutstanding;
    /** 在途提现单数（PROCESSING）。 */
    private Integer withdrawInFlight;
    /** depositHeld + advanceOutstanding。 */
    private Long totalAmount;
    /** 敞口全清（可销户）。 */
    private Boolean clear;
}