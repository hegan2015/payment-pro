package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class BalanceDO {

    private String accountId;
    private Long balance;
    private Long frozen;
    private Integer version;
    private Long updatedAt;

    /**
     * A-4/AC-6 币种账套标签（additive 列）。<b>主键仍是 account_id</b>（每账户单币种），
     * 分片键 account_id 同账户同片不变；存量行 NULL 按 CNY 读，绝不回填改写。
     */
    private String currency;
}