package com.qoder.payment.recon.dataobject;

import lombok.Data;

@Data
public class ReconReportDO {

    private String id;
    private Long checkedAt;
    private Boolean pass;
    private String report;
    /** RC-2 additive：会计日 epoch millis（实时对账 null）。 */
    private Long bizDate;
    /** RC-2 additive：REALTIME / DAILY。 */
    private String reconType;
    /** RC-3 additive：渠道码（非渠道报告 null）。 */
    private String channelCode;
}