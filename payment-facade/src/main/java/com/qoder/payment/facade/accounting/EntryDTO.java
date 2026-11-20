package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class EntryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String bizNo;
    private String accountId;
    private String dir;
    private Long amount;
    private Long balanceAfter;
    private String subject;
    private String refId;
    private Long createdAt;
    /** AC-2 会计科目代码（additive；升级前存量分录为 null，只读不回填）。 */
    private String subjectCode;
    /** AC-3 会计日（epoch millis，当日 00:00 Asia/Shanghai）；在途 TCC 用受理时快照。存量 null。 */
    private Long bizDate;

    // ---------- P2 additive（AC-6 多币种；存量分录为 null，只读不回填） ----------

    /** AC-6 分录取数币种（账套）；存量 null 按 CNY 口径读。 */
    private String currency;
    /** AC-6 入账时汇率快照（currency → 本位币 CNY），long+scale；后续汇率变动不影响已入账。 */
    private Long exchangeRate;
    /** AC-6 汇率标度。 */
    private Integer rateScale;
    /** AC-6 本位币折算额（= amount × exchangeRate / rateScale，long 精确运算）。 */
    private Long baseAmount;
}