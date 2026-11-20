package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PaymentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String orderId;
    private String bizType;
    private String method;
    private String payerAccountId;
    private String cardNo;
    private String merchantAccountId;
    private Long amount;
    private Long fee;
    private String status;
    private Long refundedAmount;
    private Long refundedFee;
    private String channelBizNo;
    private String failReason;
    private String requestId;
    private String xid;
    private Long createdAt;

    // ---------- P1 I 泳道 additive（P-2/P-5/P-6，既有字段语义不变） ----------
    /** SINGLE（默认）/ COMBO。 */
    private String payMode;
    /** P-6 路由选中渠道（CARD；BALANCE 为 null）。 */
    private String channelCode;
    /** P-5 CLOSE / CANCEL（未关闭为 null）。 */
    private String closeType;
    private Long closedAt;
    /** P-2 组合腿明细（仅 COMBO 单填充）。 */
    private List<PaymentLegDTO> legs;

    // ---------- P2 E 泳道 additive（P-7 营销抵扣编排；既有字段语义/fee 口径一字未改） ----------
    /** 营销抵扣总额（分，受理快照；无营销诉求恒 0）。 */
    private Long marketingAmount;
    /** 营销编排状态 NONE（默认）/ APPLIED / ROLLED_BACK / ROLLBACK_PENDING。 */
    private String marketingStatus;
    /**
     * 用户净实付（分）= amount − 营销净抵扣（退回后按净额收敛）。
     * <b>amount 仍是订单全额</b>（fee 基数 / settle / 对账口径不变），本字段只表达净现金流出。
     */
    private Long payAmount;

    // ---------- P2 H 泳道 additive（T-5 合并/分阶段；既有字段语义一字未改） ----------
    /** T-5 分阶段期序（仅 STAGE 单填充，1 起）。 */
    private Integer stageSeq;
    /** T-5 所属合并单 id（仅 MERGE/MERGE_COMBO 单填充）。 */
    private String mergeId;
}