package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 差错平台统计。 */
@Data
public class DisputeStatsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long total;
    private long open;
    private Map<String, Long> byStatus;
    private Map<String, Long> byType;
    private Map<String, Long> bySource;
}