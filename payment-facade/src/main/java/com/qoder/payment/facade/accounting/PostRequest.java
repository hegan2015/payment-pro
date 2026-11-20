package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 通用过账请求：bizNo 幂等，lines 借贷必相等。 */
@Data
public class PostRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String subject;
    private String refId;
    private List<TransferLineDTO> lines;

    // ---------- P2 additive（AC-6 多币种；为空时行为与改造前逐字节一致） ----------

    /**
     * AC-6 凭证币种（账套）。为空 → 由分录账户币种解析（既有账户全部 CNY）。
     * <b>一张凭证只能一个币种</b>：混币 lines 一律拒绝，跨币种必须经 exchange 拆成两张同币种凭证。
     * 注意：币种是<b>凭证级</b>标签，不进 {@code lines} JSON —— VoucherGuard 的 lines 逐字比对不受影响。
     */
    private String currency;
    /** AC-6 汇率快照（本币种 → 本位币 CNY 的折算率，long+scale，杜绝浮点）；为空由账务域取当前中间价。 */
    private Long exchangeRate;
    /** AC-6 汇率标度（如 10000 表示 rate/10000）；为空取 10000。 */
    private Integer rateScale;
}