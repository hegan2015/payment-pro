package com.qoder.payment.recon.dataobject;

import lombok.Data;

@Data
public class ReconReportDO {

    private String id;
    private Long checkedAt;
    private Boolean pass;
    private String report;
}