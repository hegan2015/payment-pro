package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class ReconCheckDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private Boolean pass;
    private String detail;
    /** RC-1 additive：本核对项差错分类（AMOUNT_MISMATCH/ONE_SIDED/STATUS_MISMATCH/CHANNEL_MISSING/SOURCE_UNAVAILABLE）。 */
    private String disputeType;
    /** RC-1 additive：缺失/不一致业务单号明细（可追溯）。 */
    private List<String> missingBizNos;
}