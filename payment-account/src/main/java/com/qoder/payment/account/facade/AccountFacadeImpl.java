package com.qoder.payment.account.facade;

import com.qoder.payment.account.card.BankCardService;
import com.qoder.payment.account.freeze.AccountFreezeService;
import com.qoder.payment.account.level.AccountLevelService;
import com.qoder.payment.account.service.AccountService;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.AccountFreezeDTO;
import com.qoder.payment.facade.account.AccountLevelDTO;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.account.BankCardDTO;
import com.qoder.payment.facade.account.BindCardRequest;
import com.qoder.payment.facade.account.FreezeRequest;
import com.qoder.payment.facade.account.LimitCheckDTO;
import com.qoder.payment.facade.account.StatusChangeRequest;
import com.qoder.payment.facade.account.UnfreezeRequest;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
public class AccountFacadeImpl implements AccountFacade {

    private final AccountService accountService;
    private final BankCardService bankCardService;
    private final AccountFreezeService accountFreezeService;
    private final AccountLevelService accountLevelService;
    /** A-5 additive：生命周期（销户/升降级）。 */
    private final com.qoder.payment.account.service.AccountLifecycleService accountLifecycleService;

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

    // ---------- P1 additive（A-1/A-2/A-3/M-3） ----------

    @Override
    public Result<AccountDTO> createTyped(String memberId, String accountType) {
        return FacadeSupport.execute(() -> accountService.createTyped(memberId, accountType));
    }

    @Override
    public Result<AccountDTO> updateStatusEx(StatusChangeRequest request) {
        return FacadeSupport.execute(() -> accountService.updateStatusEx(request));
    }

    @Override
    public Result<AccountLevelDTO> getLevel(String accountId) {
        return FacadeSupport.execute(() -> accountLevelService.getLevel(accountId));
    }

    @Override
    public Result<AccountDTO> upgradeLevel(String accountId, String targetLevel, String operator) {
        return FacadeSupport.execute(() -> accountLevelService.upgradeLevel(accountId, targetLevel, operator));
    }

    @Override
    public Result<LimitCheckDTO> checkLimit(String accountId, long amount, String kind) {
        return FacadeSupport.execute(() -> accountLevelService.checkLimit(accountId, amount, kind));
    }

    @Override
    public Result<BankCardDTO> bindCard(BindCardRequest request) {
        return FacadeSupport.execute(() -> bankCardService.bind(request));
    }

    @Override
    public Result<BankCardDTO> unbindCard(String cardId, String operator) {
        return FacadeSupport.execute(() -> bankCardService.unbind(cardId, operator));
    }

    @Override
    public Result<BankCardDTO> setDefaultCard(String accountId, String cardId) {
        return FacadeSupport.execute(() -> bankCardService.setDefault(accountId, cardId));
    }

    @Override
    public Result<List<BankCardDTO>> listCards(String accountId) {
        return FacadeSupport.execute(() -> bankCardService.list(accountId));
    }

    @Override
    public Result<AccountFreezeDTO> freezeAmount(FreezeRequest request) {
        return FacadeSupport.execute(() -> accountFreezeService.freeze(request));
    }

    @Override
    public Result<AccountFreezeDTO> unfreezeAmount(UnfreezeRequest request) {
        return FacadeSupport.execute(() -> accountFreezeService.unfreeze(request));
    }

    @Override
    public Result<List<AccountFreezeDTO>> listFreezes(String accountId, String status) {
        return FacadeSupport.execute(() -> accountFreezeService.list(accountId, status));
    }

    // ---------- P2 additive（A-4 多币种） ----------

    @Override
    public Result<AccountDTO> createCurrency(String memberId, String accountType, String currency) {
        return FacadeSupport.execute(() -> accountService.createCurrency(memberId, accountType, currency));
    }

    // ---------- P2 additive（A-5 开销户/升降级；既有方法一字未改） ----------

    @Override
    public Result<AccountDTO> closeAccount(com.qoder.payment.facade.account.AccountCloseRequest request) {
        return FacadeSupport.execute(() -> accountLifecycleService.close(request));
    }

    @Override
    public Result<AccountDTO> changeLevel(com.qoder.payment.facade.account.LevelChangeRequest request) {
        return FacadeSupport.execute(() -> accountLifecycleService.changeLevel(request));
    }

    @Override
    public Result<List<com.qoder.payment.facade.account.AccountLifecycleLogDTO>> listLifecycleLogs(String accountId) {
        return FacadeSupport.execute(() -> accountLifecycleService.logs(accountId));
    }
}