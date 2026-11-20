package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AC-4 凭证联查结果：凭证头（transfer_record，只追加不可篡改）+ 分录明细（ledger_entry）
 * + 红冲关联（ref_id 指向原凭证 / 本凭证的冲正单号）。
 */
@Data
public class VoucherDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String subject;
    private String refId;
    private Boolean reversed;
    /** NORMAL / REVERSE / SUSPENSE。 */
    private String voucherType;
    private Long bizDate;
    private Long createdAt;
    private List<TransferLineDTO> lines;
    /** 分录明细（含科目代码、会计日、过账后余额）。 */
    private List<EntryDTO> entries;
    private Long totalDebit;
    private Long totalCredit;
    private Boolean balanced;
    /** 若本凭证是冲正单，指向原凭证号（= refId）。 */
    private String origBizNo;
    /** 若本凭证已被冲正，指向冲正凭证号。 */
    private String reverseBizNo;
    /** 涉及科目代码（去重，升序）。 */
    private List<String> subjectCodes;

    // ---------- P2 additive（AC-6 多币种；存量凭证为 null） ----------

    /** AC-6 凭证币种（一张凭证一个账套）。 */
    private String currency;
    /** AC-6 凭证级汇率快照（currency → 本位币 CNY）。 */
    private Long exchangeRate;
    private Integer rateScale;
    /** AC-6 本位币折算借方发生额。 */
    private Long baseAmount;
}