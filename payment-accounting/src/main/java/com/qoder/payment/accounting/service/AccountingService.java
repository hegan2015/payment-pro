package com.qoder.payment.accounting.service;

import com.qoder.payment.accounting.dataobject.BalanceDO;
import com.qoder.payment.accounting.dataobject.EntryDO;
import com.qoder.payment.accounting.dataobject.TransferDO;
import com.qoder.payment.accounting.mapper.BalanceMapper;
import com.qoder.payment.accounting.mapper.EntryMapper;
import com.qoder.payment.accounting.mapper.TransferMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.account.AccountStatusDTO;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.EntryDTO;
import com.qoder.payment.facade.accounting.TopupRequest;
import com.qoder.payment.facade.accounting.TransferDTO;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.accounting.TrialDTO;
import com.qoder.payment.facade.account.AccountFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账务核心：复式记账与余额权威。
 *
 * <p>余额 = 贷方累计 - 借方累计；可用余额 = 余额 - 冻结。
 * 系统户（SYS_ 前缀）允许透支；非系统户借方必须有足额可用余额。
 * 多账户加锁按 accountId 排序，避免并发转账死锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingService {

    public static final String SYS_CASH = "SYS_CASH";
    public static final String SYS_CLEARING = "SYS_CLEARING";
    public static final String SYS_FEE = "SYS_FEE";

    private final BalanceMapper balanceMapper;
    private final EntryMapper entryMapper;
    private final TransferMapper transferMapper;
    private final IdempotentTemplate idempotentTemplate;

    @DubboReference
    private AccountFacade accountFacade;

    public Long topup(TopupRequest request) {
        MoneyUtil.requireValid(request.getAmount());
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        checkAccounts(List.of(request.getAccountId()));
        List<TransferLineDTO> lines = List.of(
                line(SYS_CASH, "D", request.getAmount()),
                line(request.getAccountId(), "C", request.getAmount()));
        return idempotentTemplate.execute("TOPUP", bizNo, Long.class, () -> {
            post(bizNo, lines, "充值", null, false);
            log.info("充值过账 accountId={} amount={} bizNo={}", request.getAccountId(), request.getAmount(), bizNo);
            return balanceOf(request.getAccountId());
        });
    }

    public void postIdempotent(PostRequest request) {
        String bizNo = request.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        idempotentTemplate.execute("POST", bizNo, Void.class, () -> {
            post(bizNo, request.getLines(), request.getSubject(), request.getRefId(), false);
            log.info("通用过账 bizNo={} subject={} lines={}", bizNo, request.getSubject(), request.getLines().size());
            return null;
        });
    }

    public Long balance(String accountId) {
        return balanceOf(accountId);
    }

    public Map<String, Long> balances(List<String> accountIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String id : accountIds) {
            result.put(id, balanceOf(id));
        }
        return result;
    }

    public List<EntryDTO> entries(String accountId, int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return entryMapper.selectByAccount(accountId, safeLimit).stream().map(e -> {
            EntryDTO dto = new EntryDTO();
            dto.setId(e.getId());
            dto.setBizNo(e.getBizNo());
            dto.setAccountId(e.getAccountId());
            dto.setDir(e.getDir());
            dto.setAmount(e.getAmount());
            dto.setBalanceAfter(e.getBalanceAfter());
            dto.setSubject(e.getSubject());
            dto.setRefId(e.getRefId());
            dto.setCreatedAt(e.getCreatedAt());
            return dto;
        }).toList();
    }

    public TransferDTO transfer(String bizNo) {
        TransferDO record = transferMapper.selectByBizNo(bizNo);
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "转账不存在: " + bizNo);
        }
        TransferDTO dto = new TransferDTO();
        dto.setBizNo(record.getBizNo());
        dto.setLines(TccContextUtil.fromJsonList(record.getLines(), TransferLineDTO.class));
        dto.setSubject(record.getSubject());
        dto.setRefId(record.getRefId());
        dto.setReversed(record.getReversed());
        dto.setCreatedAt(record.getCreatedAt());
        return dto;
    }

    public TrialDTO trial() {
        long debit = entryMapper.sumDebit();
        long credit = entryMapper.sumCredit();
        TrialDTO dto = new TrialDTO();
        dto.setTotalDebit(debit);
        dto.setTotalCredit(credit);
        dto.setBalanced(debit == credit);
        dto.setBalanceSum(balanceMapper.sumBalance());
        dto.setAccounts(balanceMapper.count());
        dto.setEntries(entryMapper.count());
        return dto;
    }

    /** TCC Try：按借方行冻结非系统户可用余额。 */
    public void freeze(AccountingTccContext ctx) {
        log.info("账务冻结(TCC Try) bizNo={}", ctx.getBizNo());
        validateLines(ctx.getLines());
        checkAccounts(ctx.getLines().stream().map(TransferLineDTO::getAccountId).toList());
        for (TransferLineDTO line : sorted(ctx.getLines())) {
            if (!"D".equals(line.getDir()) || isSystem(line.getAccountId())) {
                continue;
            }
            BalanceDO balance = lockOrCreate(line.getAccountId());
            long available = balance.getBalance() - balance.getFrozen();
            if (available < line.getAmount()) {
                throw new BizException(ResultCode.BIZ_ERROR, "可用余额不足: " + line.getAccountId());
            }
            balance.setFrozen(balance.getFrozen() + line.getAmount());
            balance.setUpdatedAt(System.currentTimeMillis());
            balanceMapper.update(balance);
        }
    }

    /** TCC Confirm：先解冻再过账（过账的可用余额校验不再受本笔冻结影响），同事务。 */
    public void postAndUnfreeze(AccountingTccContext ctx) {
        log.info("账务过账(TCC Confirm) bizNo={} reversed={}", ctx.getBizNo(), ctx.getReversed());
        unfreeze(ctx);
        post(ctx.getBizNo(), ctx.getLines(), ctx.getSubject(), ctx.getRefId(), Boolean.TRUE.equals(ctx.getReversed()));
    }

    /** TCC Cancel：仅解冻。 */
    public void unfreeze(AccountingTccContext ctx) {
        log.info("账务解冻(TCC Cancel) bizNo={}", ctx.getBizNo());
        for (TransferLineDTO line : sorted(ctx.getLines())) {
            if (!"D".equals(line.getDir()) || isSystem(line.getAccountId())) {
                continue;
            }
            BalanceDO balance = balanceMapper.selectForUpdate(line.getAccountId());
            if (balance == null) {
                continue;
            }
            balance.setFrozen(Math.max(0, balance.getFrozen() - line.getAmount()));
            balance.setUpdatedAt(System.currentTimeMillis());
            balanceMapper.update(balance);
        }
    }

    /** 复式记账核心：校验借贷平衡 → 排序加锁 → 逐行过账 → 落转账记录。 */
    public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed) {
        validateLines(lines);
        checkAccounts(lines.stream().map(TransferLineDTO::getAccountId).toList());
        long now = System.currentTimeMillis();
        for (TransferLineDTO line : sorted(lines)) {
            BalanceDO balance = lockOrCreate(line.getAccountId());
            long amount = line.getAmount();
            long newBalance;
            if ("D".equals(line.getDir())) {
                long available = balance.getBalance() - balance.getFrozen();
                if (!isSystem(line.getAccountId()) && available < amount) {
                    throw new BizException(ResultCode.BIZ_ERROR, "可用余额不足: " + line.getAccountId());
                }
                newBalance = balance.getBalance() - amount;
            } else {
                newBalance = balance.getBalance() + amount;
            }
            balance.setBalance(newBalance);
            balance.setUpdatedAt(now);
            balanceMapper.update(balance);

            long seq = entryMapper.nextId();
            EntryDO entry = new EntryDO();
            entry.setId("E" + seq);
            entry.setSeq(seq);
            entry.setBizNo(bizNo);
            entry.setAccountId(line.getAccountId());
            entry.setDir(line.getDir());
            entry.setAmount(amount);
            entry.setBalanceAfter(newBalance);
            entry.setSubject(subject);
            entry.setRefId(refId);
            entry.setCreatedAt(now);
            entryMapper.insert(entry);
        }
        TransferDO record = new TransferDO();
        record.setBizNo(bizNo);
        record.setLines(TccContextUtil.toJson(lines));
        record.setSubject(subject);
        record.setRefId(refId);
        record.setReversed(reversed);
        record.setCreatedAt(now);
        try {
            transferMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("转账记录已存在（幂等） bizNo={}", bizNo);
        }
    }

    private void validateLines(List<TransferLineDTO> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "分录行不能为空");
        }
        long debit = 0;
        long credit = 0;
        for (TransferLineDTO line : lines) {
            if (line.getAccountId() == null || line.getAccountId().isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "分录行账户必填");
            }
            if (!"D".equals(line.getDir()) && !"C".equals(line.getDir())) {
                throw new BizException(ResultCode.PARAM_ERROR, "dir 仅支持 D 或 C");
            }
            MoneyUtil.requireValid(line.getAmount());
            if ("D".equals(line.getDir())) {
                debit += line.getAmount();
            } else {
                credit += line.getAmount();
            }
        }
        if (debit != credit) {
            throw new BizException(ResultCode.PARAM_ERROR, "借贷不平衡: D=" + debit + " C=" + credit);
        }
    }

    private void checkAccounts(List<String> accountIds) {
        List<String> real = accountIds.stream().filter(id -> !isSystem(id)).distinct().toList();
        if (real.isEmpty()) {
            return;
        }
        Map<String, AccountStatusDTO> statuses = accountFacade.check(real).orThrow();
        for (String id : real) {
            AccountStatusDTO status = statuses.get(id);
            if (status == null) {
                throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + id);
            }
            if (!"ACTIVE".equals(status.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "账户已冻结: " + id);
            }
        }
    }

    private BalanceDO lockOrCreate(String accountId) {
        BalanceDO balance = balanceMapper.selectForUpdate(accountId);
        if (balance != null) {
            return balance;
        }
        BalanceDO fresh = new BalanceDO();
        fresh.setAccountId(accountId);
        fresh.setBalance(0L);
        fresh.setFrozen(0L);
        fresh.setVersion(0);
        fresh.setUpdatedAt(System.currentTimeMillis());
        try {
            balanceMapper.insert(fresh);
        } catch (DuplicateKeyException ignored) {
            // 并发开户竞争：对手已插入，直接加锁读取
        }
        return balanceMapper.selectForUpdate(accountId);
    }

    private long balanceOf(String accountId) {
        BalanceDO balance = balanceMapper.select(accountId);
        return balance == null ? 0L : balance.getBalance();
    }

    private static List<TransferLineDTO> sorted(List<TransferLineDTO> lines) {
        List<TransferLineDTO> copy = new ArrayList<>(lines);
        copy.sort(Comparator.comparing(TransferLineDTO::getAccountId));
        return copy;
    }

    private static boolean isSystem(String accountId) {
        return accountId != null && accountId.startsWith("SYS_");
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }
}