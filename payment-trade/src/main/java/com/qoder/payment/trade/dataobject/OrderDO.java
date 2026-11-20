package com.qoder.payment.trade.dataobject;

import lombok.Data;

@Data
public class OrderDO {

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

    // ---------- P1 · G 泳道 additive 列（trade_order 只增列，既有列与唯一键一字未改） ----------

    /** T-3 产品码 INSTANT/ESCROW/PREAUTH（存量行 DB 默认 INSTANT）。 */
    private String productType;
    /** T-1 担保卖家会员 id（ESCROW 时 merchantId 为平台担保系统商户）。 */
    private String payeeMerchantId;
    /** T-1 担保子状态 HOLD/CONFIRMED/AUTO_CONFIRMED（非 ESCROW 恒 NULL）。 */
    private String escrowStatus;
    /** T-4/T-1 当前状态超时截止时刻（epoch millis）。 */
    private Long expireAt;

    // ---------- P2 · H 泳道 T-5/T-6 additive 列（只增列，既有列与唯一键一字未改） ----------

    /** T-5 所属合并单 id（未入合并 NULL）。 */
    private String mergeId;
    /** T-5 分阶段总期数（无计划 NULL）。 */
    private Integer stageCount;
    /** T-5 已付期数（DB 默认 0）。 */
    private Integer paidStage;
    /** T-5 已累计实付额（分，DB 默认 0）。 */
    private Long paidAmount;
    /** T-6 首次改价前原始金额（未改价 NULL）。 */
    private Long originalAmount;
    /** T-6 累计改价次数（DB 默认 0）。 */
    private Integer priceChangeCount;
}