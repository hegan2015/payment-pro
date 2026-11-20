package com.qoder.payment.accounting.dataobject;

import lombok.Data;

@Data
public class EntryDO {

    private String id;
    private Long seq;
    private String bizNo;
    private String accountId;
    private String dir;
    private Long amount;
    private Long balanceAfter;
    private String subject;
    private String refId;
    private Long createdAt;
    /** AC-2 会计科目代码（additive 列；存量行为 null，绝不回填改写）。 */
    private String subjectCode;
    /** AC-3 会计日（additive 列；在途 TCC 用受理时快照）。 */
    private Long bizDate;

    // ---------- P2 additive（AC-6 多币种；存量行为 null，绝不回填改写） ----------

    /** AC-6 币种账套（存量 NULL 按 CNY 读）。 */
    private String currency;
    /** AC-6 入账时汇率快照（currency → 本位币 CNY）。 */
    private Long exchangeRate;
    private Integer rateScale;
    /** AC-6 本位币折算额（= amount × exchangeRate / rateScale）。 */
    private Long baseAmount;
}