package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class BalanceDO {

    private String accountId;
    private Long balance;
    private Long frozen;
    private Integer version;
    private Long updatedAt;
}