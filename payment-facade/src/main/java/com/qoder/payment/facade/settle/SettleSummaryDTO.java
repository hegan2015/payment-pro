package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SettleSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer payCount;
    private Long totalGross;
    private Long totalFee;
    private Long totalNet;
    private Long totalRefund;
}