package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-4 提现单：PROCESSING（受理+冻结在途）→ SUCCESS（银行到账）/ FAILED（失败已解冻退回）。
 *
 * <p>账务三腿（均复式记账、各自 bizNo 幂等）：
 * 受理 {@code WH:<id>} D 账户 / C SYS_PAYOUT（冻结在途）；
 * 到账 {@code WP:<id>} D SYS_PAYOUT / C SYS_CASH；
 * 失败 {@code WR:<id>} D SYS_PAYOUT / C 账户（回滚解冻）。
 */
@Data
public class WithdrawOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String accountId;
    private String merchantId;
    private Long amount;
    private Long fee;
    private String bankCardNo;
    private String channelCode;
    private String channelOrderNo;
    private String status;
    private String requestId;
    private String bizNo;
    private Integer reconcileCount;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
}