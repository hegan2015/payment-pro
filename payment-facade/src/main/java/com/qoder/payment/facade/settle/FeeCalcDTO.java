package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-2 计费预览结果：source 标明命中层（RULE/CYCLE/CONFIG/DEFAULT），便于证明缺省口径未变。 */
@Data
public class FeeCalcDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String merchantId;
    private String productType;
    private String method;
    private Long amount;
    private Integer feeBps;
    private Long fee;
    private Long net;
    private String feeType;
    private String ruleId;
    private Integer ruleVersion;
    /** RULE / CYCLE / CONFIG / DEFAULT。 */
    private String source;
}