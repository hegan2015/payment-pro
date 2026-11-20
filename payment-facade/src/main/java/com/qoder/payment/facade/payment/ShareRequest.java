package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * P-3 分账编排请求（payment 域算比例/固定额建单，settle 域只收结果守恒校验登记划转）。
 *
 * <p>receiver 二选一：{@code amount} 固定额（分）或 {@code ratioBps} 净额比例（万分比）。
 * Σ分账 ≤ 支付净额（amount−fee）−已分账额，超额拒绝；全额分账时 Σ=净额误差 0。
 * 幂等键：requestId（share_order.request_id UNIQUE）。
 */
@Data
public class ShareRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String requestId;
    /** REALTIME（默认）/ DELAYED（延迟分账，到期由调度驱动）。 */
    private String shareType;
    /** DELAYED 延迟毫秒（可空=配置默认）。 */
    private Long delayMs;
    private List<Receiver> receivers;

    @Data
    public static class Receiver implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String accountId;
        /** 固定额（分），与 ratioBps 二选一。 */
        private Long amount;
        /** 净额比例（万分比 0~10000），与 amount 二选一。 */
        private Integer ratioBps;
    }
}