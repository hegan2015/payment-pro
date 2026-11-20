package com.qoder.payment.trade.dataobject;

import lombok.Data;

/** T-2 预授权完成扣款留痕（trade 库，单库不分片；request_id UNIQUE ⇒ 调用方幂等键）。 */
@Data
public class PreAuthCompleteDO {

    /** PC + seq：同时作为账务 post 的 bizNo（确定性重试见 PreAuthService）。 */
    private String id;
    private String orderId;
    private Long amount;
    private String bizNo;
    private String requestId;
    /** 本次释放的原冻结单 id。 */
    private String releasedFreezeId;
    /** 差额重冻单 id（全额完成为 NULL）。 */
    private String refreezeId;
    /** PENDING/DONE（远端步骤进度，崩溃后可重放收敛）。 */
    private String status;
    private Long createdAt;
}