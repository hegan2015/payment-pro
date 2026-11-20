package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/** AC-3 日终汇总中间行：某账户某会计日的借贷发生额（GROUP BY account_id，分片键 ⇒ 组不跨片）。 */
@Data
public class AccountDayTotalDO {

    private String accountId;
    private Long debitTotal;
    private Long creditTotal;
    private Integer entryCount;
}