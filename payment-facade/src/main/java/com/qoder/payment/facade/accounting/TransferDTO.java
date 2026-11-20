package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TransferDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private List<TransferLineDTO> lines;
    private String subject;
    private String refId;
    private Boolean reversed;
    private String reverseBizNo;
    /** 凭证类型：NORMAL/REVERSE/SUSPENSE（S-6）。 */
    private String voucherType;
    private Long createdAt;
    /** AC-3 会计日（additive；升级前存量凭证为 null）。 */
    private Long bizDate;

    // ---------- P2 additive（AC-6 多币种；存量凭证为 null） ----------

    /** AC-6 凭证币种（一张凭证一个账套，绝不混币）。 */
    private String currency;
    /** AC-6 凭证级汇率快照（currency → 本位币 CNY）。 */
    private Long exchangeRate;
    /** AC-6 汇率标度。 */
    private Integer rateScale;
    /** AC-6 本位币折算借方发生额。 */
    private Long baseAmount;
}