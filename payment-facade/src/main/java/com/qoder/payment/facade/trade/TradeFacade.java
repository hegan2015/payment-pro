package com.qoder.payment.facade.trade;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 交易域：交易单生命周期，不触碰资金。对标支付宝交易核心。 */
public interface TradeFacade {

    Result<OrderDTO> createOrder(OrderCreateRequest request);

    Result<List<OrderDTO>> listOrders();

    Result<OrderDTO> getOrder(String orderId);

    Result<OrderDTO> closeOrder(String orderId);

    /** 退款标记。bizNo 幂等，由支付核心 outbox 可靠投递。 */
    Result<OrderDTO> refundMark(String orderId, long amount, String bizNo);

    // ==================================================================
    // P1 · G 泳道交易产品工作流（T-3/T-1/T-2/T-4）——以下全部 additive，
    // 上方既有方法签名/语义/幂等键（merchant+out_trade_no）一字未改。
    // ==================================================================

    /** T-3 产品目录与路由参数（只读）：启用开关、状态机、DynamicConfig 生效参数。 */
    Result<List<TradeProductDTO>> listProducts();

    /**
     * T-1 担保确认收货：D 担保户 / C 卖家（净额），(ESCROW_CONFIRM,orderId) 幂等 + escrow_status CAS。
     * 仅 ESCROW 且 escrow_status=HOLD 可确认；重复调用返回首次结果（不重复打款）。
     */
    Result<OrderDTO> confirmReceive(String orderId);

    /**
     * T-2 预授权冻结：复用 account A-3 {@code freezeAmount(PREAUTH)}（只调 facade），
     * frozen+=authAmount、balance 不动（available=balance−frozen 口径不变）。requestId 幂等。
     */
    Result<OrderDTO> preAuth(String orderId, String accountId, String requestId);

    /**
     * T-2 预授权完成扣款：amount ≤ 冻结额−已完成额（超扣拒绝），差额解冻；
     * (PREAUTH_COMPLETE,requestId) 幂等，账务 post bizNo 确定性 ⇒ 重试不重扣。
     */
    Result<OrderDTO> completePreAuth(String orderId, long amount, String requestId);

    /** T-2 预授权撤销：剩余冻结额全额解冻（已完成扣款不回滚），(PREAUTH_CANCEL,requestId) 幂等。 */
    Result<OrderDTO> cancelPreAuth(String orderId, String requestId);

    /**
     * T-4 超时关闭扫描（作业/运维触发入口，与 JobHandler {@code tradeOrderExpire} 同一实现）：
     * 仅 CREATED 且 expire_at 到期、且无在途支付单的订单 CAS 关闭；按 orderId CRC32 分片不重不漏。
     * 返回本次实际关闭笔数。
     */
    Result<Integer> closeExpiredOrders(int shardIndex, int shardTotal);

    /**
     * T-1 担保超时自动确认扫描（作业/运维触发入口，与 JobHandler {@code escrowAutoConfirm} 同一实现）：
     * 仅 ESCROW + PAID + HOLD 且 expire_at 到期，按 orderId CRC32 分片不重不漏。返回本次确认笔数。
     */
    Result<Integer> autoConfirmEscrow(int shardIndex, int shardTotal);

    // ==================================================================
    // P2 · H 泳道 T-5/T-6（合并支付/分阶段 + 改价）——全部 additive，
    // 上方既有方法签名/语义/幂等键（merchant+out_trade_no）一字未改。
    // ==================================================================

    /**
     * T-5 合并单创建：≥2 笔同商户 CREATED INSTANT TRADE 订单（无阶段计划/无在途合并），
     * total=Σ子订单额（误差 0）。(MERGE_CREATE,requestId) + merge_order.request_id UNIQUE 幂等；
     * 子订单 merge_id CAS 占位，任一失败整体拒绝（本地事务）。
     */
    Result<MergeOrderDTO> createMergeOrder(MergeCreateRequest request);

    /** T-5 合并单查询（含子订单实时状态/已退额与 fee 分摊快照）。 */
    Result<MergeOrderDTO> getMergeOrder(String mergeId);

    /**
     * T-5 合并支付受理占位（payment 域编排调用）：CAS CREATED→PAYING + 绑定 paymentId/承载单 +
     * 复核子订单仍 CREATED 且金额与快照一致（改价竞态守卫）+ 落 rel fee 分摊。失败 0 行即拒绝。
     */
    Result<MergeOrderDTO> prepareMergePay(String mergeId, String paymentId, String carrierOrderId, java.util.List<Long> relFees);

    /** T-5 合并单重开（整体回滚/支付单失败后）：PAYING→CREATED，子订单须已释放，承载单 CREATED 则关闭。幂等。 */
    Result<MergeOrderDTO> reopenMerge(String mergeId);

    /**
     * T-5 合并单拆分（明确拆分语义）：仅 CREATED（未付/整体回滚/撤销释放后）可拆，
     * 子订单解绑后可个别支付/改价；合并单 DISSOLVED 终态留痕。幂等。
     */
    Result<MergeOrderDTO> dissolveMerge(String mergeId);

    /**
     * T-5 分阶段计划：CREATED INSTANT 订单设 N 期（≥2），Σ期额=订单额（误差 0），
     * (STAGE_PLAN,orderId:requestId) 幂等；已有计划/合并/在途支付拒绝。
     */
    Result<OrderDTO> createStagePlan(String orderId, java.util.List<Long> amounts, String requestId);

    /**
     * T-6 未支付改价：仅 CREATED 且 payment_id IS NULL（与受理 reserve CAS 互斥），
     * newAmount 校验 + original_amount 首次留痕 + price_change_count 累加 + order_price_log 审计。
     * (PRICE_CHANGE,orderId:requestId) + request_id UNIQUE 幂等。已支付拒绝（走 PaymentFacade.adjustPrice）。
     */
    Result<OrderDTO> changePrice(String orderId, long newAmount, String reason, String requestId);

    /**
     * T-6 已支付改价留痕（payment 域差额补退编排后调用）：只写 order_price_log，不动 amount/状态。
     * (PRICE_CHANGE,orderId:requestId) 幂等。direction=REFUND_DELTA/SUPPLEMENT/NONE。
     */
    Result<OrderDTO> markPriceChange(String orderId, long newAmount, String reason, String requestId,
                                     String direction, String refundId, String supplementOrderId);

    /** T-6 改价留痕查询（审计）。 */
    Result<java.util.List<PriceChangeLogDTO>> listPriceLogs(String orderId);

    /**
     * T-5/T-6 合并单当日撤销后释放（payment 域撤销 CAS 胜出后调用，幂等）：
     * 子订单 PAID→CREATED（payment_id/refunded=0 CAS 守卫）、承载单 PAID→CLOSED、合并单 PAID→CREATED 可重付。
     */
    Result<MergeOrderDTO> releaseMergeAfterCancel(String mergeId, String paymentId);

    /**
     * T-5/T-6 阶段支付当日撤销后回退（幂等）：paid_stage 回退一期、paid_amount 减本期额、
     * 订单回到 CREATED（首期）/PAID（指向上期）；仅最新已付期可撤（CAS 守卫）。
     */
    Result<OrderDTO> releaseStageAfterCancel(String orderId, String paymentId);
}