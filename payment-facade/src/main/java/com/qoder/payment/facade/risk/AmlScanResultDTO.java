package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** R-6 监测扫描结果（实时命中 + 日切扫描共用；幂等：重复扫描不重复建报告）。 */
@Data
public class AmlScanResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizDate;
    /** 扫描到的付款方数。 */
    private Integer payers;
    /** 扫描的交易笔数。 */
    private Integer txns;
    /** 参与评估的规则数。 */
    private Integer rules;
    /** 命中次数（含幂等复用）。 */
    private Integer hits;
    /** 新建报告数。 */
    private Integer created;
    /** 幂等复用既有报告数。 */
    private Integer reused;
    /** 护栏降级次数（规则编译失败/执行异常/超时 → 跳过并告警）。 */
    private Integer degraded;
    private List<Long> reportIds = new ArrayList<>();
    private List<String> hitRules = new ArrayList<>();
    private Long elapsedMillis;
}