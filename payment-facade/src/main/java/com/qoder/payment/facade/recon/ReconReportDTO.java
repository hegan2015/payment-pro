package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class ReconReportDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private Long checkedAt;
    private Boolean pass;
    private List<ReconCheckDTO> checks;
    /** RC-1 additive：本次对账生成/自动核销的差错单 id（X-1 联动）。 */
    private List<Long> disputeIds;
    /** RC-2 additive：会计日 epoch millis（实时对账为 null）。 */
    private Long bizDate;
    /** RC-2 additive：REALTIME / DAILY（历史报告反序列化为 null，按 REALTIME 解读）。 */
    private String reconType;
    /** RC-3 additive：渠道内外对账渠道码（非渠道报告为 null）。 */
    private String channelCode;
}