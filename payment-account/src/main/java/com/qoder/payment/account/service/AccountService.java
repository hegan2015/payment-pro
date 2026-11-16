package com.qoder.payment.account.service;

import com.qoder.payment.account.convert.AccountConverter;
import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;

    @DubboReference
    private MemberFacade memberFacade;

    @DubboReference
    private AccountingFacade accountingFacade;

    public AccountDTO create(String memberId) {
        MemberDTO member = memberFacade.get(memberId).orThrow();
        AccountDO account = new AccountDO();
        account.setId("A" + accountMapper.nextId());
        account.setMemberId(memberId);
        account.setMemberName(member.getName());
        account.setType(member.getType());
        account.setStatus("ACTIVE");
        account.setCreatedAt(System.currentTimeMillis());
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            AccountDO existing = accountMapper.selectByMemberId(memberId);
            throw new BizException(ResultCode.CONFLICT, "该会员已开户: " + (existing == null ? memberId : existing.getId()));
        }
        log.info("账户开立 accountId={} memberId={} type={}", account.getId(), memberId, account.getType());
        return AccountConverter.toDTO(account, 0L);
    }

    public List<AccountDTO> list(String memberId) {
        List<AccountDO> accounts = accountMapper.selectAll();
        if (memberId != null && !memberId.isBlank()) {
            accounts = accounts.stream().filter(a -> memberId.equals(a.getMemberId())).toList();
        }
        if (accounts.isEmpty()) {
            return List.of();
        }
        // 锁外聚合账务域余额，避免跨域持锁等待
        List<String> ids = accounts.stream().map(AccountDO::getId).toList();
        Map<String, Long> balances = accountingFacade.balances(ids).orThrow();
        List<AccountDTO> result = new ArrayList<>();
        for (AccountDO account : accounts) {
            result.add(AccountConverter.toDTO(account, balances.getOrDefault(account.getId(), 0L)));
        }
        return result;
    }

    public AccountDTO get(String accountId) {
        AccountDO account = require(accountId);
        long balance = accountingFacade.balance(accountId).orThrow();
        return AccountConverter.toDTO(account, balance);
    }

    public AccountDTO getByMember(String memberId) {
        AccountDO account = accountMapper.selectByMemberId(memberId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员未开户: " + memberId);
        }
        return AccountConverter.toDTO(account, 0L);
    }

    public AccountDTO updateStatus(String accountId, String status) {
        if (!"ACTIVE".equals(status) && !"FROZEN".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ACTIVE 或 FROZEN");
        }
        require(accountId);
        accountMapper.updateStatus(accountId, status);
        log.info("账户状态变更 accountId={} status={}", accountId, status);
        return get(accountId);
    }

    public Map<String, AccountStatusDTO> check(List<String> accountIds) {
        Map<String, AccountStatusDTO> result = new LinkedHashMap<>();
        for (String id : accountIds) {
            AccountDO account = require(id);
            AccountStatusDTO status = new AccountStatusDTO();
            status.setStatus(account.getStatus());
            status.setType(account.getType());
            result.put(id, status);
        }
        return result;
    }

    private AccountDO require(String accountId) {
        AccountDO account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        return account;
    }
}