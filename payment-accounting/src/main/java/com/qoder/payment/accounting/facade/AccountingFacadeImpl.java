package com.qoder.payment.accounting.facade;

import com.qoder.payment.accounting.service.AccountingService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.EntryQuery;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.common.PageDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
public class AccountingFacadeImpl implements AccountingFacade {

    private final AccountingService accountingService;

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
    public Result<TrialDTO> trial() {
        return FacadeSupport.execute(accountingService::trial);
    }
}