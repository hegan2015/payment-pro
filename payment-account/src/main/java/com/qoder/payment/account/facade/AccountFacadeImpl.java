package com.qoder.payment.account.facade;

import com.qoder.payment.account.service.AccountService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.AccountStatusDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
public class AccountFacadeImpl implements AccountFacade {

    private final AccountService accountService;

    @Override
    public Result<AccountDTO> create(String memberId) {
        return FacadeSupport.execute(() -> accountService.create(memberId));
    }

    @Override
    public Result<List<AccountDTO>> list(String memberId) {
        return FacadeSupport.execute(() -> accountService.list(memberId));
    }

    @Override
    public Result<AccountDTO> get(String accountId) {
        return FacadeSupport.execute(() -> accountService.get(accountId));
    }

    @Override
    public Result<AccountDTO> getByMember(String memberId) {
        return FacadeSupport.execute(() -> accountService.getByMember(memberId));
    }

    @Override
    public Result<AccountDTO> updateStatus(String accountId, String status) {
        return FacadeSupport.execute(() -> accountService.updateStatus(accountId, status));
    }

    @Override
    public Result<Map<String, AccountStatusDTO>> check(List<String> accountIds) {
        return FacadeSupport.execute(() -> accountService.check(accountIds));
    }
}