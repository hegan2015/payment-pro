package com.qoder.payment.facade.cashier;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** C-3 设置/重置支付密码（沙箱，哈希加盐落 cashier 库；明文不落库/日志）。 */
@Data
public class SetPayPasswordRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String accountId;
    /** 6 位数字支付密码（沙箱）。 */
    private String password;
    /** 重置时校验旧密码（可选；首次设置可空）。 */
    private String oldPassword;
}