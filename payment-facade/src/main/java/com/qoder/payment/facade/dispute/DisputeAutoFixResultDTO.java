package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** autofix/run 结果：SHADOW=影子模式只给建议不动账；EXECUTE=按策略执行修复。 */
@Data
public class DisputeAutoFixResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String mode;
    private int total;
    private int fixed;
    /** SHADOW：建议策略的 OPEN 差错单；EXECUTE：处理后的差错单快照。 */
    private List<DisputeDTO> items = new ArrayList<>();
    /** EXECUTE 模式下执行失败的差错单 id → 原因。 */
    private Map<String, String> errors = new LinkedHashMap<>();
}