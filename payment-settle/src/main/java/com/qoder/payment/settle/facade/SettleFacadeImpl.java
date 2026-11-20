package com.qoder.payment.settle.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.settle.FeeCalcDTO;
import com.qoder.payment.facade.settle.FeeCalcRequest;
import com.qoder.payment.facade.settle.FeeRuleDTO;
import com.qoder.payment.facade.settle.MerchantBillDTO;
import com.qoder.payment.facade.settle.SettleBatchDTO;
import com.qoder.payment.facade.settle.SettleBatchRunDTO;
import com.qoder.payment.facade.settle.SettleCycleDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.facade.settle.SettleSummaryDTO;
import com.qoder.payment.facade.settle.ShareSettleDTO;
import com.qoder.payment.facade.settle.ShareSettleRequest;
import com.qoder.payment.facade.settle.ShareSummaryDTO;
import com.qoder.payment.facade.settle.WithdrawOrderDTO;
import com.qoder.payment.facade.settle.WithdrawRequest;
import com.qoder.payment.settle.bill.MerchantBillService;
import com.qoder.payment.settle.fee.FeeRuleService;
import com.qoder.payment.settle.service.SettleBatchService;
import com.qoder.payment.settle.service.SettleCycleService;
import com.qoder.payment.settle.service.SettleService;
import com.qoder.payment.settle.service.SettleShareService;
import com.qoder.payment.settle.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class SettleFacadeImpl implements SettleFacade {

    private final SettleService settleService;
    private final SettleCycleService cycleService;
    private final SettleBatchService batchService;
    private final FeeRuleService feeRuleService;
    private final SettleShareService shareService;
    private final WithdrawService withdrawService;
    private final MerchantBillService billService;
    /** ST-6 additive：保证金 / 授信垫资。 */
    private final com.qoder.payment.settle.service.DepositService depositService;
    private final com.qoder.payment.settle.service.CreditService creditService;

    @Override
    public Result<SettleRecordDTO> recordPay(SettlePayRequest request) {
        return FacadeSupport.execute(() -> settleService.recordPay(request));
    }

    @Override
    public Result<SettleRecordDTO> recordRefund(SettleRefundRequest request) {
        return FacadeSupport.execute(() -> settleService.recordRefund(request));
    }

    @Override
    public Result<List<SettleRecordDTO>> records(String merchantId) {
        return FacadeSupport.execute(() -> settleService.records(merchantId));
    }

    @Override
    public Result<SettleSummaryDTO> summary() {
        return FacadeSupport.execute(settleService::summary);
    }

    // ================= ST-1 T+N 结算 =================

    @Override
    public Result<SettleCycleDTO> getCycle(String merchantId) {
        return FacadeSupport.execute(() -> cycleService.get(merchantId));
    }

    @Override
    public Result<SettleCycleDTO> saveCycle(SettleCycleDTO cycle) {
        return FacadeSupport.execute(() -> cycleService.save(cycle));
    }

    @Override
    public Result<List<SettleBatchDTO>> listBatches(String merchantId, Long bizDate) {
        return FacadeSupport.execute(() -> batchService.list(merchantId, bizDate));
    }

    @Override
    public Result<SettleBatchDTO> getBatch(String batchId) {
        return FacadeSupport.execute(() -> batchService.get(batchId));
    }

    @Override
    public Result<SettleBatchRunDTO> runSettleBatch(Long bizDate, int shardIndex, int shardTotal, boolean force) {
        return FacadeSupport.execute(() -> batchService.run(bizDate, shardIndex, shardTotal, force));
    }

    // ================= ST-2 费率配置 =================

    @Override
    public Result<List<FeeRuleDTO>> listFeeRules(String merchantId) {
        return FacadeSupport.execute(() -> feeRuleService.list(merchantId));
    }

    @Override
    public Result<FeeRuleDTO> saveFeeRule(FeeRuleDTO rule) {
        return FacadeSupport.execute(() -> feeRuleService.save(rule));
    }

    @Override
    public Result<FeeCalcDTO> calcFee(FeeCalcRequest request) {
        return FacadeSupport.execute(() -> feeRuleService.calc(request));
    }

    // ================= ST-3 分账（结算侧） =================

    @Override
    public Result<ShareSettleDTO> recordShare(ShareSettleRequest request) {
        return FacadeSupport.execute(() -> shareService.recordShare(request));
    }

    @Override
    public Result<ShareSettleDTO> recordShareBack(ShareSettleRequest request) {
        return FacadeSupport.execute(() -> shareService.recordShareBack(request));
    }

    @Override
    public Result<List<ShareSettleDTO>> shares(String shareId) {
        return FacadeSupport.execute(() -> shareService.shares(shareId));
    }

    @Override
    public Result<ShareSummaryDTO> shareSummary(String shareId) {
        return FacadeSupport.execute(() -> shareService.shareSummary(shareId));
    }

    // ================= ST-4 提现到银行 =================

    @Override
    public Result<WithdrawOrderDTO> withdraw(WithdrawRequest request) {
        return FacadeSupport.execute(() -> withdrawService.withdraw(request));
    }

    @Override
    public Result<WithdrawOrderDTO> getWithdraw(String withdrawId) {
        return FacadeSupport.execute(() -> withdrawService.get(withdrawId));
    }

    @Override
    public Result<WithdrawOrderDTO> withdrawReconcile(String withdrawId) {
        return FacadeSupport.execute(() -> withdrawService.reconcile(withdrawId));
    }

    @Override
    public Result<WithdrawOrderDTO> withdrawReceipt(String withdrawId, String status, String reason) {
        return FacadeSupport.execute(() -> withdrawService.receipt(withdrawId, status, reason));
    }

    // ================= ST-5 商户账单 =================

    @Override
    public Result<MerchantBillDTO> generateBill(String merchantId, Long bizDate, String billType) {
        return FacadeSupport.execute(() -> billService.generate(merchantId, bizDate, billType));
    }

    @Override
    public Result<List<MerchantBillDTO>> listBills(String merchantId) {
        return FacadeSupport.execute(() -> billService.list(merchantId));
    }

    @Override
    public Result<String> billCsv(String billId) {
        return FacadeSupport.execute(() -> billService.csv(billId));
    }

    // ================= ST-6 保证金/垫资（P2 G 泳道 additive；既有方法一字未改） =================

    @Override
    public Result<com.qoder.payment.facade.settle.DepositRecordDTO> deposit(
            com.qoder.payment.facade.settle.DepositRequest request) {
        return FacadeSupport.execute(() -> depositService.deposit(request));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.DepositRecordDTO> releaseDeposit(
            com.qoder.payment.facade.settle.DepositRequest request) {
        return FacadeSupport.execute(() -> depositService.release(request));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.DepositRecordDTO> deductDeposit(
            com.qoder.payment.facade.settle.DepositRequest request) {
        return FacadeSupport.execute(() -> depositService.deduct(request));
    }

    @Override
    public Result<List<com.qoder.payment.facade.settle.DepositRecordDTO>> listDeposits(String merchantId, String type) {
        return FacadeSupport.execute(() -> depositService.list(merchantId, type));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.DepositRecordDTO> getDeposit(String depositId) {
        return FacadeSupport.execute(() -> depositService.get(depositId));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.DepositSummaryDTO> depositSummary(String merchantId) {
        return FacadeSupport.execute(() -> depositService.summary(merchantId));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.CreditAccountDTO> grantCredit(
            com.qoder.payment.facade.settle.CreditAccountDTO credit) {
        return FacadeSupport.execute(() -> creditService.grant(credit));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.CreditAccountDTO> getCredit(String merchantId) {
        return FacadeSupport.execute(() -> creditService.get(merchantId));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.AdvanceRecordDTO> advancePay(
            com.qoder.payment.facade.settle.AdvanceRequest request) {
        return FacadeSupport.execute(() -> creditService.advance(request));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.AdvanceRecordDTO> recoverAdvance(
            com.qoder.payment.facade.settle.AdvanceRequest request) {
        return FacadeSupport.execute(() -> creditService.recover(request));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.AdvanceRecordDTO> getAdvance(String advanceId) {
        return FacadeSupport.execute(() -> creditService.getAdvance(advanceId));
    }

    @Override
    public Result<List<com.qoder.payment.facade.settle.AdvanceRecordDTO>> listAdvances(String merchantId, String type) {
        return FacadeSupport.execute(() -> creditService.list(merchantId, type));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.CreditSummaryDTO> creditSummary(String merchantId) {
        return FacadeSupport.execute(() -> creditService.summary(merchantId));
    }

    @Override
    public Result<com.qoder.payment.facade.settle.FundExposureDTO> fundExposure(String memberId, String accountId) {
        return FacadeSupport.execute(() -> creditService.exposure(memberId, accountId));
    }
}