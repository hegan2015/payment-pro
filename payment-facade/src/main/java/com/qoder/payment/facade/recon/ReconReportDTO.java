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
}