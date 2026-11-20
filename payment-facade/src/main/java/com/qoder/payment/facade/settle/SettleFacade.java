package com.qoder.payment.facade.settle;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 清结算域：结算单（商户净额）与手续费账单，T+0 演示结算。对标支付宝清结算系统。 */
public interface SettleFacade {

    /** paymentId 幂等。 */
    Result<SettleRecordDTO> recordPay(SettlePayRequest request);

    /** bizId 幂等。 */
    Result<SettleRecordDTO> recordRefund(SettleRefundRequest request);

    Result<List<SettleRecordDTO>> records(String merchantId);

    Result<SettleSummaryDTO> summary();

    // ======================= P1 第二期 · 清结算工作流（ST-1~ST-5，additive） =======================
    // 以上四个 P0 方法签名/语义/幂等键逐字节不变（ride §0.7 红线）。

    // ---- ST-1 T+N 结算 ----

    /** 商户结算周期（无配置返回动态默认 T+N，不落库）。 */
    Result<SettleCycleDTO> getCycle(String merchantId);

    /** 保存商户结算周期（upsert，热生效于下一次跑批）。 */
    Result<SettleCycleDTO> saveCycle(SettleCycleDTO cycle);

    /** 批次查询（merchantId/bizDate 均可空）。 */
    Result<List<SettleBatchDTO>> listBatches(String merchantId, Long bizDate);

    Result<SettleBatchDTO> getBatch(String batchId);

    /**
     * 日切跑批：按商户 T+N 汇总到期结算单出批次。
     *
     * @param bizDate    指定业务日（可空=窗口内全部到期日）
     * @param shardIndex 分片序号（0 起）
     * @param shardTotal 分片总数（>=1，按 merchant CRC32 取模，不重不漏）
     * @param force      true=忽略 T+N 期限（运维补跑，仍幂等不重结）
     */
    Result<SettleBatchRunDTO> runSettleBatch(Long bizDate, int shardIndex, int shardTotal, boolean force);

    // ---- ST-2 费率配置 ----

    Result<List<FeeRuleDTO>> listFeeRules(String merchantId);

    /** 保存费率规则（upsert + version 自增 + 变更审计）；只影响新单，在途单用受理快照。 */
    Result<FeeRuleDTO> saveFeeRule(FeeRuleDTO rule);

    /** 计费预览（只算不落库）。 */
    Result<FeeCalcDTO> calcFee(FeeCalcRequest request);

    // ---- ST-3 分账（结算侧登记 + 动账，编排属 P-3） ----

    /** (SETTLE_SHARE, shareId:receiverAccountId) 幂等。 */
    Result<ShareSettleDTO> recordShare(ShareSettleRequest request);

    /** (SETTLE_SHARE_BACK, shareId:receiverAccountId) 幂等。 */
    Result<ShareSettleDTO> recordShareBack(ShareSettleRequest request);

    Result<List<ShareSettleDTO>> shares(String shareId);

    /** 分账守恒视图（Σ分账 = 结算净额，误差 0）。 */
    Result<ShareSummaryDTO> shareSummary(String shareId);

    // ---- ST-4 提现到银行（沙箱出款） ----

    /** requestId 幂等；受理即冻结在途，终态才确认/解冻。 */
    Result<WithdrawOrderDTO> withdraw(WithdrawRequest request);

    Result<WithdrawOrderDTO> getWithdraw(String withdrawId);

    /** 出金补单：查渠道/沙箱终态收敛（回执丢失兜底，幂等不重扣）。 */
    Result<WithdrawOrderDTO> withdrawReconcile(String withdrawId);

    /** 沙箱银行回执入口（仅沙箱，等价于渠道异步回执；幂等，终态只翻一次）。 */
    Result<WithdrawOrderDTO> withdrawReceipt(String withdrawId, String status, String reason);

    // ---- ST-5 商户账单/对账单 ----

    /** 幂等 (merchantId,bizDate,billType)；billType=DAILY/MONTHLY。 */
    Result<MerchantBillDTO> generateBill(String merchantId, Long bizDate, String billType);

    Result<List<MerchantBillDTO>> listBills(String merchantId);

    /** 账单 CSV 全文（网关下载用；明细 + 汇总）。 */
    Result<String> billCsv(String billId);

    // ======================= P2 第三期 · G 泳道（ST-6 保证金/垫资，additive） =======================
    // 以上全部既有方法签名/语义/幂等键逐字节不变（ride §0.7 红线）。
    // 保证金/垫资全部经既有 AccountingFacade.post 复式记账，不自建账本；
    // 系统户复用 SYS_DEPOSIT（2006 保证金，已登记）与 SYS_ADVANCE（垫资应收，待主会话补科目）。

    // ---- ST-6 保证金 ----

    /** 缴存：D 出资账户 / C SYS_DEPOSIT。(DEPOSIT_FREEZE,requestId) 幂等。 */
    Result<DepositRecordDTO> deposit(DepositRequest request);

    /** 释放：D SYS_DEPOSIT / C 原出资账户。(DEPOSIT_RELEASE,requestId) 幂等；敏感需 step-up（网关）。 */
    Result<DepositRecordDTO> releaseDeposit(DepositRequest request);

    /** 扣划：D SYS_DEPOSIT / C 去向账户。(DEPOSIT_DEDUCT,requestId) 幂等；敏感需 step-up（网关）。 */
    Result<DepositRecordDTO> deductDeposit(DepositRequest request);

    /** 保证金单据（merchantId 可空=全部；type 可空=全部）。 */
    Result<List<DepositRecordDTO>> listDeposits(String merchantId, String type);

    Result<DepositRecordDTO> getDeposit(String depositId);

    /** 保证金守恒视图（SYS_DEPOSIT 余额 == 未释放保证金，误差 0）。 */
    Result<DepositSummaryDTO> depositSummary(String merchantId);

    // ---- ST-6 授信/垫资 ----

    /** 授信额度维护（upsert；额度不得低于已占用；status ACTIVE/FROZEN）。 */
    Result<CreditAccountDTO> grantCredit(CreditAccountDTO credit);

    Result<CreditAccountDTO> getCredit(String merchantId);

    /** 垫资：额度内原子扣减 + D SYS_ADVANCE / C 商户账户。(ADVANCE,requestId) 幂等。 */
    Result<AdvanceRecordDTO> advancePay(AdvanceRequest request);

    /** 追偿：D 商户账户 / C SYS_ADVANCE + 释放额度。(ADVANCE_RECOVER,requestId) 幂等。 */
    Result<AdvanceRecordDTO> recoverAdvance(AdvanceRequest request);

    Result<AdvanceRecordDTO> getAdvance(String advanceId);

    Result<List<AdvanceRecordDTO>> listAdvances(String merchantId, String type);

    /** 授信/垫资守恒视图（used == 未回收 == −balance(SYS_ADVANCE)）。 */
    Result<CreditSummaryDTO> creditSummary(String merchantId);

    /** A-5 销户护栏用资金敞口（未释放保证金 + 未回收垫资 + 在途提现），只读。 */
    Result<FundExposureDTO> fundExposure(String memberId, String accountId);
}