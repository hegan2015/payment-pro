package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AccountStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String status;
    private String type;

    // ---------- P1 additive：账务域现仅消费 status（非 ACTIVE 全拒），以下供方向闸门接线 ----------

    /** A-1 账户类型。 */
    private String accountType;
    /** A-3 状态冻结类型 NONE/FULL/JUDICIAL。 */
    private String freezeType;

    /** A-4 账户币种（additive；既有账户/存量行缺省 CNY）。账务域按此分账套，绝不跨币种相加。 */
    private String currency;
}