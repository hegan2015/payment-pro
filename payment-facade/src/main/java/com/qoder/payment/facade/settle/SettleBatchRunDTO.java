package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** ST-1 一次跑批的结果留痕（分片参数 + 到期判定 + 逐批状态）。 */
@Data
public class SettleBatchRunDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    private Integer shardIndex;
    private Integer shardTotal;
    /** 本片命中商户数（按 merchant CRC32 % shardTotal 取模，不重不漏）。 */
    private Integer merchants;
    private Integer created;
    private Integer settled;
    private Integer failed;
    /** 未到 T+N 期限被跳过的 (merchant,bizDate) 数。 */
    private Integer notDue;
    /** 已 SETTLED 幂等跳过数。 */
    private Integer skipped;
    private List<SettleBatchDTO> batches = new ArrayList<>();
}