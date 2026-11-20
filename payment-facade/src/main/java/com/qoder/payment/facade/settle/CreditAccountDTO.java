package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-6 商户授信额度（used 原子扣减，并发不超授信）。 */
@Data
public class CreditAccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private Long creditLimit;
    /** 已占用额度 = Σ未回收垫资。 */
    private Long used;
    /** ACTIVE / FROZEN（FROZEN 禁止新垫资，回收不受影响）。 */
    private String status;
    private Integer version;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}