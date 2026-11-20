package com.qoder.payment.trade.dataobject;

import lombok.Data;

/**
 * T-1 系统担保户登记（trade 库，单库不分片；name PK）。
 * 沿用支付核心 SYS_FUND 先例：系统商户（MERCHANT）+ 其余额账户即平台担保户。
 */
@Data
public class TradeSystemAccountDO {

    private String name;
    private String memberId;
    private String accountId;
    private Long createdAt;
    private Long updatedAt;
}