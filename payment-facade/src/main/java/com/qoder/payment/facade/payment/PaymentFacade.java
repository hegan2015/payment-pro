package com.qoder.payment.facade.payment;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * 支付核心：支付单 + TCC 协调跨域分布式事务，
 * 本地消息表（outbox）可靠投递清结算/通知，异步补偿调度兜底。对标支付宝支付核心。
 */
public interface PaymentFacade {

    Result<PayResultDTO> pay(PayRequest request);

    /** 资金操作支付（充值/提现）：无手续费，走系统资金商户，仍经 TCC+清结算。 */
    Result<PayResultDTO> fundPay(FundPayRequest request);

    Result<RefundResultDTO> refund(String paymentId, RefundRequest request);

    Result<List<PaymentDTO>> listPayments();

    /** additive：退款单分页列表（paymentId/status 可选过滤），供管理台退款单列表页。 */
    Result<com.qoder.payment.facade.common.PageDTO<com.qoder.payment.facade.payment.RefundDTO>> listRefunds(
            String paymentId, String status, int page, int size);

    Result<PaymentDTO> getPayment(String paymentId);

    /** 查询补单：对在途/终态不一致支付单主动收敛（幂等）。 */
    Result<PaymentDTO> reconverge(String paymentId);

    // ==================================================================
    // P1 · I 泳道支付编排工作流（P-2/P-3/P-4/P-5 + CHALLENGE 续付）——
    // 以下全部 additive，上方既有方法签名/语义/幂等键一字未改（ride §0.7 红线）。
    // ==================================================================

    /** P-3 分账编排：比例/固定额计算建单，Σ≤净额−已分；执行经 outbox(PP_SHARE) 投递 settle 登记动账。requestId 幂等。 */
    Result<ShareOrderDTO> share(ShareRequest request);

    /** P-3 分账回退（整单反向划转）：(SHARE_BACK,shareId) 幂等，已回退拒绝重复。 */
    Result<ShareOrderDTO> shareBack(ShareBackRequest request);

    Result<ShareOrderDTO> getShare(String shareId);

    Result<List<ShareOrderDTO>> listShares(String paymentId);

    /** P-3 手动/补单驱动分账执行（幂等：settle 登记 (SETTLE_SHARE,shareId:receiver) + 账务 bizNo 确定性）。 */
    Result<ShareOrderDTO> executeShare(String shareId);

    /** P-4 退款单查询（独立终态，异步退款轮询）。 */
    Result<RefundDTO> getRefund(String refundId);

    /** P-4 退款补单收敛：查渠道终态经 TCC resume 驱动（幂等，不重复过账）。 */
    Result<RefundDTO> refundReconverge(String refundId);

    /** P-5 关闭在途支付单（TCC 回滚释放，CAS 守卫防与受理竞态悬挂）。(PAY_CLOSE,paymentId) 幂等。 */
    Result<PaymentDTO> close(String paymentId, String requestId);

    /** P-5 当日撤销（已 SUCCESS 未清算全额反向过账，区别于退款）。(PAY_CANCEL,paymentId) 幂等。 */
    Result<PaymentDTO> cancel(String paymentId, String requestId);

    /** 风控 CHALLENGE 续付：C-3 verifyToken 核销后以新 requestId 放行 CHALLENGE 分级（其余分级仍拒绝）。 */
    Result<PayResultDTO> continueChallenge(ChallengeContinueRequest request);

    /** P-5 关闭竞态悬挂修复（存量兜底，幂等）：CLOSED 单晚到分支冻结/订单 PAYING 的补偿释放。 */
    Result<PaymentDTO> repairCloseRace(String paymentId);

    /**
     * P360 受控终态修复：dtx=CONFIRMED 且凭证齐全的历史竞态脏单 FAILED→SUCCESS。
     * 只翻状态不动资金；前置不满足 409；大额双人复核/二次认证由网关闸门承担。
     */
    Result<java.util.Map<String, Object>> repairTerminal(String paymentId, String reason, String requestId, String operator);

    // ==================================================================
    // P2 · E 泳道 P-7 营销抵扣编排 —— 全部 additive。
    // 上方既有方法签名/语义/幂等键一字未改；pay(PayRequest) 的 marketing 字段缺省 null
    // 时编排完全短路（零 RPC / 零额外延迟 / fee 与 BALANCE 同步语义不变，ride §0.7 红线）。
    // ==================================================================

    /** P-7 营销抵扣明细（只读，可追溯）：明细行 + 分摊 + 守恒三件套；无营销诉求返回 NONE 空明细。 */
    Result<MarketingDeductionDTO> getMarketingDeduction(String paymentId);

    /**
     * P-7 营销抵扣回退（幂等，(PAY_MARKETING_ROLLBACK,paymentId) 语义）：
     * 逆序退回已核销权益（券/红包/积分/活动预算 + 资金反向 D 用户户/C SYS_MARKETING），
     * 随后<b>目标维度重分摊</b>使 Σ目标 = Σ来源（diff=0）。失败留 ROLLBACK_PENDING 由作业兜底重试。
     */
    Result<MarketingDeductionDTO> rollbackMarketing(String paymentId, String requestId);

    /**
     * P-7 退回后目标维度重分摊（幂等）：按营销域当前来源净额重算目标分布（最大余数法），
     * Σ目标分摊 = Σ来源核销净额，diff=0；只调整目标维度调整行，来源维度（资金凭证 1:1）只追加不改。
     */
    Result<MarketingDeductionDTO> reallocateMarketing(String paymentId, String requestId);

    // ==================================================================
    // P2 · H 泳道 T-5/T-6（合并支付/分阶段 + 已支付改价）——全部 additive，
    // 上方既有方法签名/语义/幂等键（payment.request_id UNIQUE）一字未改。
    // ==================================================================

    /**
     * T-5 合并支付：一笔支付付多笔子订单。Σ子订单额=合并额（误差 0，受理前复核快照），
     * 单全局事务多 trade 分支（承载单 PAY + 各子订单 MERGE_PAY），一子订单 Try 失败整体回滚、
     * 合并单重开可重付。legs 非空时叠加 COMBO（Σ腿=合并额，每腿独立 accounting 分支 bizNo=legId）。
     * request_id UNIQUE 幂等；BALANCE 同步受理→PROCESSING→SUCCESS（与单腿同一完结监听）。
     */
    Result<PayResultDTO> payMerge(MergePayRequest request);

    /**
     * T-5 分阶段付款：stageSeq 顺序支付（=paidStage+1），每期独立支付单 + 独立 TCC +
     * (STAGE_PAY,orderId:stageSeq) 占位幂等；Σ期额=订单额（计划创建时强校验），
     * 全款后 paid_stage=stage_count 且订单 PAID。request_id UNIQUE 幂等。
     */
    Result<PayResultDTO> payStage(StagePayRequest request);

    /**
     * T-6 已支付改价（差额补退）：下调经退款单差额退（P-4 异步收敛不变），
     * 上调建补差订单并 BALANCE 支付；不改 trade_order.amount/fee 快照，留痕 order_price_log。
     * (PRICE_ADJUST,requestId) 幂等。大额闸门在网关 /api 控制器（step-up + 双人复核）。
     */
    Result<AdjustPriceDTO> adjustPrice(AdjustPriceRequest request);
}