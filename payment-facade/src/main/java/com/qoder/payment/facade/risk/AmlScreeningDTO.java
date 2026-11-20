package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-6 名单筛查留痕（制裁 SANCTION / 政要 PEP，沙箱名单；命中与否都留痕可追溯）。 */
@Data
public class AmlScreeningDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 筛查对象类型：PAYER / MERCHANT。 */
    private String subjectType;
    /** 筛查对象（脱敏：首尾保留）。 */
    private String subjectMask;
    private String listType;
    private Boolean hit;
    /** 命中的名单条目 id（未命中 null）。 */
    private String matchedEntryId;
    /** 命中方式：EXACT / ALIAS / CONTAINS。 */
    private String matchType;
    private Long reportId;
    private String traceId;
    private Long createdAt;
}