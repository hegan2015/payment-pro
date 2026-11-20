package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A-5 销户请求（生命周期终结，<b>逻辑销户不物理删</b>）。
 *
 * <p>幂等键 {@code (ACCOUNT_CLOSE, requestId)}；同一账户重复销户恒返回既有 CLOSED 快照。
 * <p>护栏（服务端权威，任一不满足即拒绝、绝不动资金）：账务余额=0、无 ACTIVE 金额冻结单、
 * 账户状态 ACTIVE 且 freezeType=NONE、无在途支付/提现、无未释放保证金/未回收垫资。
 */
@Data
public class AccountCloseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String accountId;
    /** 幂等键（必填，≤60 字符）。 */
    private String requestId;
    /** 操作员（网关恒取会话用户，不接受 body 冒充）。 */
    private String operator;
    /** 销户原因（留痕）。 */
    private String reason;
}