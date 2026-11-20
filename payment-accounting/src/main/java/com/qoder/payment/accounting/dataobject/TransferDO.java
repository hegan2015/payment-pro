package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class TransferDO {

    private String bizNo;
    private String lines;
    private String subject;
    private String refId;
    private Boolean reversed;
    /** 凭证类型：NORMAL/REVERSE/SUSPENSE（S-6，落库后只读）。 */
    private String voucherType;
    private Long createdAt;
    /** AC-3 会计日（additive 列；凭证头只追加，落库后不可篡改）。 */
    private Long bizDate;

    // ---------- P2 additive（AC-6 多币种；存量行为 null） ----------

    private String currency;
    private Long exchangeRate;
    private Integer rateScale;
    private Long baseAmount;
}