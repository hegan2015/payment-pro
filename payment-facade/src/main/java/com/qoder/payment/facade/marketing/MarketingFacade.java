package com.qoder.payment.facade.marketing;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 营销域（X-3a 红包/优惠券 · X-3b 积分/活动核销 + 分摊联动）。
 *
 * <p><b>资金红线（IMPLEMENTATION-PLAN §5.5）</b>：营销资金属新资金域，但<b>不自建资金账本</b>——
 * 一切真实资金变动（券/红包/活动/积分抵扣及其退回）都经 {@code AccountingFacade.post} 复式记账
 * （{@code D SYS_MARKETING / C 用户户}，科目 6002 营销支出），bizNo 确定性派生 + 本地幂等表 + 状态 CAS
 * 三重防重；积分数量本身是<b>虚拟权益独立账本</b>（points_account/points_batch/points_log），
 * 不参与 Σbalance=0，只在换算成金额那一刻进入资金账本。
 *
 * <p><b>范围红线</b>：本接口<b>不含</b> P-7 营销抵扣编排（支付腿组合/全局事务）与 C-4 最优组合推荐。
 * {@link #allocate} / {@link #checkConservation} 是为 P-7 预留的<b>分摊与守恒校验</b>能力，
 * 由后续 payment 域编排调用，本域不主动触碰 payment/trade/settle 的任何单据。
 *
 * <p><b>ride 兼容红线</b>：本域全部为新增能力，不改动 8 个冻结 OpenAPI 方法、BALANCE 同步语义、
 * feeBps→SYS_FEE 手续费口径、notify http 推送与六项对账判定。
 */
public interface MarketingFacade {

    // ==================== X-3a 模板 ====================

    /** 新建/更新模板（传 id 即幂等 upsert；库存/已发放数只增不减）。 */
    Result<MarketingTemplateDTO> saveTemplate(MarketingTemplateDTO template);

    Result<MarketingTemplateDTO> getTemplate(String templateId);

    /** kind/status 可为空表示不过滤。 */
    Result<List<MarketingTemplateDTO>> listTemplates(String kind, String status);

    // ==================== X-3a 优惠券 ====================

    /** 发券：(COUPON_ISSUE,bizNo) + coupon.request_id UNIQUE 幂等。 */
    Result<CouponDTO> issueCoupon(CouponIssueRequest request);

    /** 领券：模板库存原子扣减 + 每人限领 + (CLAIM,templateId:memberId:bizNo) 幂等。 */
    Result<CouponDTO> claimCoupon(ClaimRequest request);

    Result<CouponDTO> getCoupon(String couponId);

    /** status 可空；memberId 必填。 */
    Result<List<CouponDTO>> listCoupons(String memberId, String status);

    /** 核销：(COUPON_USE,couponId:paymentId) + request_id UNIQUE + coupon_id UNIQUE + AVAILABLE→USED CAS。 */
    Result<CouponUseDTO> useCoupon(CouponUseRequest request);

    /** 退回：USED→REFUNDED，资金反向（D 用户户 / C SYS_MARKETING），券回到 AVAILABLE。幂等 (COUPON_REFUND,useId)。 */
    Result<CouponUseDTO> refundCoupon(String couponUseId);

    // ==================== X-3a 红包 ====================

    Result<RedPacketDTO> issueRedPacket(RedPacketIssueRequest request);

    Result<RedPacketDTO> claimRedPacket(ClaimRequest request);

    Result<RedPacketDTO> getRedPacket(String redPacketId);

    Result<List<RedPacketDTO>> listRedPackets(String memberId, String status);

    Result<CouponUseDTO> useRedPacket(RedPacketUseRequest request);

    Result<CouponUseDTO> refundRedPacket(String redPacketUseId);

    // ==================== X-3a 可用优惠 / 过期 ====================

    /** 可用券/红包/活动 + 积分余额（只读，不核销；orderAmount 可空表示不做门槛过滤）。 */
    Result<AvailablePromotionDTO> listAvailable(String memberId, Long orderAmount);

    /** 过期作业：AVAILABLE 且 expire_at ≤ now 的券/红包 → EXPIRED（幂等，返回本次处理张数）。 */
    Result<Integer> expireVouchers();

    // ==================== X-3b 积分 ====================

    Result<PointsAccountDTO> pointsAccount(String memberId);

    /** 赚取（纯虚拟权益，按批次落有效期）：(POINTS_EARN,bizNo) 幂等。 */
    Result<PointsLogDTO> earnPoints(PointsEarnRequest request);

    /** 抵扣（积分→金额，资金侧复式记账）：(POINTS_USE,bizNo) 幂等；fromFrozen=true 走冻结转消耗。 */
    Result<PointsLogDTO> usePoints(PointsUseRequest request);

    /** 冻结（不动资金）：(POINTS_FREEZE,bizNo) 幂等。 */
    Result<PointsLogDTO> freezePoints(PointsFreezeRequest request);

    /** 解冻（不动资金）：(POINTS_UNFREEZE,bizNo) 幂等。 */
    Result<PointsLogDTO> unfreezePoints(PointsFreezeRequest request);

    /** 退回积分抵扣：资金反向 + 积分回补（新批次，沿用原有效期）。幂等 (POINTS_REFUND,origBizNo)。 */
    Result<PointsLogDTO> refundPoints(String origBizNo, String requestId);

    Result<List<PointsLogDTO>> pointsLogs(String memberId, int limit);

    /** 积分过期作业：批次 remaining 清零 + 账户余额扣减 + EXPIRE 流水（幂等，返回过期积分数）。 */
    Result<Long> expirePoints();

    // ==================== X-3b 活动 ====================

    Result<ActivityDTO> saveActivity(ActivityDTO activity);

    Result<ActivityDTO> getActivity(String activityId);

    /** orderAmount 非空时只返回门槛命中且未过期/未耗尽的活动。 */
    Result<List<ActivityDTO>> listActivities(Long orderAmount);

    /** 活动核销（立减/满减）：预算原子扣减 + (ACTIVITY_APPLY,activityId:paymentId) 幂等 + 复式记账。 */
    Result<ActivityUseDTO> applyActivity(ActivityApplyRequest request);

    /** 活动退回：预算回滚 + 资金反向。幂等 (ACTIVITY_REFUND,useId)。 */
    Result<ActivityUseDTO> refundActivity(String activityUseId);

    // ==================== X-3b 分摊联动（P-7 预留接口，不含编排） ====================

    /**
     * 营销抵扣额在订单/商品/商户间的分摊：WEIGHT 最大余数法 / EXPLICIT 显式金额，
     * <b>Σ分摊 = 抵扣总额，误差 0</b>；不守恒直接拒绝（绝不落半成品分摊行）。
     */
    Result<MarketingAllocDTO> allocate(MarketingAllocRequest request);

    /** 查分摊（来源维度 + 目标维度）。 */
    Result<MarketingAllocDTO> getAllocation(String paymentId);

    /** 守恒校验（只读）：订单额 = 资金腿 + 营销腿，且营销腿 = Σ来源 = Σ目标。 */
    Result<MarketingConservationDTO> checkConservation(String paymentId, Long orderAmount, Long fundAmount);

    // ==================== 资金护航 / 审计 ====================

    /** 营销资金恒等核对 + 账务试算平衡（只读）。 */
    Result<MarketingFundCheckDTO> fundCheck();

    /** 审计留痕（entityId 可空表示全量，倒序 limit 条）。 */
    Result<List<MarketingAuditDTO>> listAudit(String entityId, int limit);

    // ==================== P2 · E 泳道 additive（C-4 最优组合 / P-7 重分摊） ====================
    // 上方既有方法签名/语义/幂等键一字未改；以下两项为新增能力，不改动任何既有核销/分摊口径。

    /**
     * C-4 营销计算：在可用券/红包/积分/活动约束下求<b>用户最优（实付最小）</b>组合。
     *
     * <p>只读计算，<b>不核销、不动账、不写单据</b>；考虑满减门槛/封顶/互斥/叠加/积分抵现比例/活动预算余额，
     * 给出选中理由与落选原因（可解释）、同输入同输出（确定性 + inputFingerprint）、
     * 候选爆炸时<b>无损剪枝</b>并留痕（绝不超预算、绝不违反互斥）。核销由 P-7 编排在支付受理时执行。
     */
    Result<BestPromotionDTO> calcBestPromotion(BestPromotionRequest request);

    /**
     * P-7 退回后<b>目标维度重分摊</b>（幂等，X-3b 遗留项）：按当前来源净额（Σ核销−Σ退回）
     * 重算目标分布（最大余数法，权重=原目标 USE 额），使 {@code Σ目标 = Σ来源，diff=0}。
     *
     * <p>只追加/收敛目标维度 REFUND 调整行（UNIQUE(payment_id,dimension,source,target_id,type)），
     * <b>来源维度与目标 USE 行绝不改写</b>（资金凭证 1:1 审计链完整）；每次重分摊落审计留痕。
     * 来源净额上升超过既有目标 USE 总额时拒绝（需先 allocate 新目标），绝不落不守恒分摊。
     */
    Result<MarketingAllocDTO> reallocate(String paymentId, String requestId);
}