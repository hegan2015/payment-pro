package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    private String merchantName;
    private String outTradeNo;
    private String bizType;
    private String subject;
    private Long amount;
    private String status;
    private String paymentId;
    private Long refundedAmount;
    private Long createdAt;
    private Long paidAt;
    private Long closedAt;

    // ---------- P1 · G 泳道 T-1/T-2/T-3/T-4 additive 只读出参 ----------
    // 既有字段（id/merchantId/amount/status/paymentId/refundedAmount/...）语义与取值一字未改；
    // 以下字段对 INSTANT 默认产品除 productType/expireAt 外恒为 null（ride trade.query 增量对账口径不变）。

    /** T-3 交易产品：INSTANT/ESCROW/PREAUTH（存量订单回填 INSTANT）。 */
    private String productType;
    /** T-1 担保卖家会员 id（ESCROW 时 merchantId 为平台担保系统商户，本字段才是真实卖家）。 */
    private String payeeMerchantId;
    /** T-1 担保状态：HOLD（担保中）/CONFIRMED（手动确认收货）/AUTO_CONFIRMED（超时自动确认）。 */
    private String escrowStatus;
    /** T-4/T-1 当前状态超时截止时刻（epoch millis）：CREATED=未支付关闭期限；ESCROW HOLD=自动确认期限。 */
    private Long expireAt;
    /** T-2 预授权付款账户（非 PREAUTH 为 null）。 */
    private String preauthAccountId;
    /** T-2 预授权冻结额（分）。 */
    private Long preauthAmount;
    /** T-2 已累计完成扣款额（分，≤ 冻结额）。 */
    private Long preauthCompleted;
    /** T-2 预授权状态：AUTHORIZED/PARTIAL_COMPLETED/COMPLETED/CANCELLED。 */
    private String preauthStatus;
    /** T-2 当前生效的 A-3 冻结单 id（部分完成后为差额重冻单）。 */
    private String preauthFreezeId;

    // ---------- P2 · H 泳道 T-5/T-6 additive 只读出参 ----------
    // 非合并/非分阶段/未改价订单以下字段恒为 null/0 ⇒ ride trade.query 口径不变。

    /** T-5 所属合并单 id（未入合并为 null）。 */
    private String mergeId;
    /** T-5 分阶段总期数（无计划为 null）。 */
    private Integer stageCount;
    /** T-5 已付期数（无计划为 null）。 */
    private Integer paidStage;
    /** T-5 已累计实付额（分）= Σ已付期额；全款时 = amount（守恒）。 */
    private Long paidAmount;
    /** T-5 各期计划额（按 stageSeq 升序；无计划为 null）。 */
    private java.util.List<Long> stageAmounts;
    /** T-6 首次改价前原始金额（未改价为 null）。 */
    private Long originalAmount;
    /** T-6 累计改价次数（默认 0）。 */
    private Integer priceChangeCount;
}