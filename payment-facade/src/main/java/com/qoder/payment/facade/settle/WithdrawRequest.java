package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-4 提现到银行受理请求（控制台/商户结算侧出金，沙箱渠道）。
 *
 * <p><b>与 ride 红线无关</b>：ride 的 {@code fund.withdraw} 仍走 OpenAPI → PaymentFacade.fundPay
 * 同步路径，语义与返回逐字节不变；本请求只服务新增的 {@code /api/settle/withdraw}。
 * requestId 幂等（UK）。
 */
@Data
public class WithdrawRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    private Long amount;
    /** 收款银行卡号（沙箱：末位约定 FAIL/PENDING 可注入失败/异步，不接真实银行）。 */
    private String bankCardNo;
    private String requestId;
    /** 提现手续费（分，可空；缺省按 settle.withdraw.fee-bps 默认 0）。 */
    private Long fee;
}