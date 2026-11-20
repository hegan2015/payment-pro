package com.qoder.payment.accounting.facade;

import com.qoder.payment.accounting.daycut.AccountingDayService;
import com.qoder.payment.accounting.fx.FxExchangeService;
import com.qoder.payment.accounting.fx.FxRateService;
import com.qoder.payment.accounting.ledger.LedgerQueryService;
import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.accounting.service.ReserveCheckService;
import com.qoder.payment.accounting.service.ReserveReconService;
import com.qoder.payment.accounting.subject.SubjectService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.accounting.AccountingDayDTO;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.DayEndReportDTO;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.EntryQuery;
import com.qoder.payment.facade.accounting.ExchangeRequest;
import com.qoder.payment.facade.accounting.ExchangeResultDTO;
import com.qoder.payment.facade.accounting.FxRateDTO;
import com.qoder.payment.facade.accounting.FxRateSetRequest;
import com.qoder.payment.facade.accounting.GeneralLedgerDTO;
import com.qoder.payment.facade.accounting.LedgerQuery;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.ReserveAdjustRequest;
import com.qoder.payment.facade.accounting.ReserveCheckDTO;
import com.qoder.payment.facade.accounting.ReserveReconDTO;
import com.qoder.payment.facade.accounting.ReverseRequest;
import com.qoder.payment.facade.accounting.ReverseResultDTO;
import com.qoder.payment.facade.accounting.SubjectBalanceDTO;
import com.qoder.payment.facade.accounting.SubjectDTO;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.accounting.VoucherDTO;
import com.qoder.payment.facade.accounting.VoucherQuery;
import com.qoder.payment.facade.common.PageDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
public class AccountingFacadeImpl implements AccountingFacade {

    private final AccountingService accountingService;
    private final ReserveCheckService reserveCheckService;
    private final SubjectService subjectService;
    private final AccountingDayService accountingDayService;
    private final LedgerQueryService ledgerQueryService;
    private final ReserveReconService reserveReconService;
    /** P2-C A-4 汇率 / 换汇（additive 注入，既有构造参数顺序不变）。 */
    private final FxRateService fxRateService;
    private final FxExchangeService fxExchangeService;

    @Override
    public Result<Long> topup(TopupRequest request) {
        return FacadeSupport.execute(() -> accountingService.topup(request));
    }

    @Override
    public Result<Void> post(PostRequest request) {
        return FacadeSupport.execute(() -> {
            accountingService.postIdempotent(request);
            return null;
        });
    }

    @Override
    public Result<Long> balance(String accountId) {
        return FacadeSupport.execute(() -> accountingService.balance(accountId));
    }

    @Override
    public Result<Map<String, Long>> balances(List<String> accountIds) {
        return FacadeSupport.execute(() -> accountingService.balances(accountIds));
    }

    @Override
    public Result<List<EntryDTO>> entries(String accountId, int limit) {
        return FacadeSupport.execute(() -> accountingService.entries(accountId, limit));
    }

    @Override
    public Result<PageDTO<EntryDTO>> entriesPage(EntryQuery query) {
        return FacadeSupport.execute(() -> accountingService.entriesPage(query));
    }

    @Override
    public Result<TransferDTO> transfer(String bizNo) {
        return FacadeSupport.execute(() -> accountingService.transfer(bizNo));
    }

    @Override
    public Result<ReverseResultDTO> reverse(ReverseRequest request) {
        return FacadeSupport.execute(() -> accountingService.reverse(request));
    }

    @Override
    public Result<TransferDTO> suspensePost(PostRequest request) {
        return FacadeSupport.execute(() -> accountingService.suspensePost(request));
    }

    @Override
    public Result<Long> suspenseBalance() {
        return FacadeSupport.execute(accountingService::suspenseBalance);
    }

    @Override
    public Result<TrialDTO> trial() {
        return FacadeSupport.execute(accountingService::trial);
    }

    @Override
    public Result<ReserveCheckDTO> reserveCheck() {
        return FacadeSupport.execute(reserveCheckService::check);
    }

    @Override
    public Result<List<ReserveCheckDTO>> reserveLogs(int limit) {
        return FacadeSupport.execute(() -> reserveCheckService.logs(limit));
    }

    @Override
    public Result<ReserveCheckDTO> reserveAdjust(ReserveAdjustRequest request) {
        return FacadeSupport.execute(() -> reserveCheckService.adjust(request));
    }

    // ==================================================================
    // P1-E 账务工作流（AC-2/AC-3/AC-4/AC-5）——additive，既有方法一字未改
    // ==================================================================

    @Override
    public Result<List<SubjectDTO>> listSubjects() {
        return FacadeSupport.execute(subjectService::listSubjects);
    }

    @Override
    public Result<List<SubjectBalanceDTO>> listSubjectBalance(String subjectCode, Long bizDate) {
        return FacadeSupport.execute(() -> ledgerQueryService.balanceSheet(bizDate).stream()
                .filter(x -> subjectCode == null || subjectCode.isBlank() || subjectCode.equals(x.getSubjectCode()))
                .toList());
    }

    @Override
    public Result<SubjectBalanceDTO> getSubjectBalance(String subjectCode, Long bizDate) {
        return FacadeSupport.execute(() -> ledgerQueryService.subjectBalance(subjectCode, bizDate));
    }

    @Override
    public Result<SubjectDTO> mapAccountSubject(String accountId, String subjectCode) {
        return FacadeSupport.execute(() -> subjectService.mapAccountSubject(accountId, subjectCode));
    }

    @Override
    public Result<String> subjectOfAccount(String accountId) {
        return FacadeSupport.execute(() -> subjectService.subjectOf(accountId));
    }

    @Override
    public Result<AccountingDayDTO> currentAccountingDay() {
        return FacadeSupport.execute(accountingDayService::current);
    }

    @Override
    public Result<List<AccountingDayDTO>> listAccountingDays(int limit) {
        return FacadeSupport.execute(() -> accountingDayService.list(limit));
    }

    @Override
    public Result<AccountingDayDTO> runDayCut(Long bizDate, int shardIndex, int shardTotal) {
        return FacadeSupport.execute(() -> accountingDayService.runDayCut(bizDate, shardIndex, shardTotal));
    }

    @Override
    public Result<DayEndReportDTO> runDayEnd(Long bizDate, int shardIndex, int shardTotal) {
        return FacadeSupport.execute(() -> accountingDayService.runDayEnd(bizDate, shardIndex, shardTotal));
    }

    @Override
    public Result<DayEndReportDTO> getDayEndReport(Long bizDate) {
        return FacadeSupport.execute(() -> accountingDayService.getReport(bizDate));
    }

    @Override
    public Result<PageDTO<GeneralLedgerDTO>> generalLedger(String subjectCode, Long bizDate, int page, int size) {
        return FacadeSupport.execute(() -> ledgerQueryService.generalLedger(subjectCode, bizDate, page, size));
    }

    @Override
    public Result<PageDTO<EntryDTO>> detailLedger(LedgerQuery query) {
        return FacadeSupport.execute(() -> ledgerQueryService.detailLedger(query));
    }

    @Override
    public Result<List<SubjectBalanceDTO>> balanceSheet(Long bizDate) {
        return FacadeSupport.execute(() -> ledgerQueryService.balanceSheet(bizDate));
    }

    @Override
    public Result<PageDTO<VoucherDTO>> vouchers(VoucherQuery query) {
        return FacadeSupport.execute(() -> ledgerQueryService.vouchers(query));
    }

    @Override
    public Result<VoucherDTO> voucher(String bizNo) {
        return FacadeSupport.execute(() -> ledgerQueryService.voucher(bizNo));
    }

    @Override
    public Result<ReserveReconDTO> reserveRecon(Long bizDate, List<String> channels) {
        return FacadeSupport.execute(() -> reserveReconService.recon(bizDate, channels));
    }

    @Override
    public Result<List<ReserveCheckDTO>> reserveReconLogs(Long bizDate, String channelCode, int limit) {
        return FacadeSupport.execute(() -> reserveReconService.logs(bizDate, channelCode, limit));
    }

    // ==================================================================
    // P2-C（A-4 多币种+汇率 / AC-6 多币种账务）——additive，既有方法一字未改
    // ==================================================================

    @Override
    public Result<FxRateDTO> getFxRate(String fromCurrency, String toCurrency, Long version) {
        return FacadeSupport.execute(() -> fxRateService.getFxRate(fromCurrency, toCurrency, version));
    }

    @Override
    public Result<List<FxRateDTO>> listFxRates(String fromCurrency, String toCurrency, int limit) {
        return FacadeSupport.execute(() -> fxRateService.listFxRates(fromCurrency, toCurrency, limit));
    }

    @Override
    public Result<FxRateDTO> setFxRate(FxRateSetRequest request) {
        return FacadeSupport.execute(() -> fxRateService.setFxRate(request));
    }

    @Override
    public Result<List<String>> listCurrencies() {
        return FacadeSupport.execute(fxRateService::currencies);
    }

    @Override
    public Result<ExchangeResultDTO> exchange(ExchangeRequest request) {
        return FacadeSupport.execute(() -> fxExchangeService.exchange(request));
    }

    @Override
    public Result<ExchangeResultDTO> fxExchange(String bizNo) {
        return FacadeSupport.execute(() -> fxExchangeService.get(bizNo));
    }

    @Override
    public Result<TrialDTO> trial(String currency) {
        return FacadeSupport.execute(() -> accountingService.trial(currency));
    }
}