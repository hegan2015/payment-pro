package com.qoder.payment.account.freeze;

import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.dataobject.AccountFreezeDO;
import com.qoder.payment.account.mapper.AccountFreezeMapper;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountFreezeDTO;
import com.qoder.payment.facade.account.FreezeReason;
import com.qoder.payment.facade.account.FreezeRequest;
import com.qoder.payment.facade.account.UnfreezeRequest;
import com.qoder.payment.facade.accounting.AccountingTccContext;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.tcc.TccBranchRequest;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * A-3 冻结细分（金额级，按原因 RISK/JUDICIAL/PREAUTH/DISPUTE）：
 * <ul>
 *   <li>冻结/解冻经账务 TCC 参与者 Try/Cancel 落地 frozen 列——available=balance−frozen 口径不变，
 *       TCC 支付冻结语义不受影响（参与者模板自带幂等/空回滚/防悬挂）；</li>
 *   <li>行政冻结单无协调器 dtx 记录，不会被 recover 自动驱动；xid=ADMF-bizNo 确定性幂等；</li>
 *   <li>对手腿 SYS_FREEZE 为虚拟系统户（freeze/unfreeze 只动非系统借方腿，不产生分录/余额行），
 *       试算平衡 D=C 与 Σbalance=0 不受冻结影响；</li>
 *   <li>解冻必须对应原冻结原因；JUDICIAL 解冻需 authorizedBy≠operator 双人复核；</li>
 *   <li>冻结单 + account_freeze_log 全程凭证留痕；bizNo 幂等（冻结/解冻各自幂等键）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountFreezeService {

    /** 虚拟对手腿（系统户前缀语义不变；仅满足参与者借贷平衡校验，不落分录/余额）。 */
    static final String SYS_FREEZE = "SYS_FREEZE";
    private static final String XID_PREFIX = "ADMF-";

    private final AccountFreezeMapper freezeMapper;
    private final AccountMapper accountMapper;
    private final FreezeLogService freezeLogService;

    @DubboReference(group = "accounting", check = false, timeout = 5000, retries = 0)
    private TccParticipantFacade accountingTcc;

    public AccountFreezeDTO freeze(FreezeRequest request) {
        if (request == null || request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        if (request.getBizNo() == null || request.getBizNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填（幂等键）");
        }
        if (request.getAmount() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "amount 必填");
        }
        MoneyUtil.requireValid(request.getAmount());
        FreezeReason reason = parseReason(request.getReasonType());
        AccountDO account = requireActive(request.getAccountId());
        AccountFreezeDO existing = freezeMapper.selectByBizNo(request.getBizNo());
        if (existing != null) {
            assertSame(existing, request, reason);
            return toDTO(existing);
        }
        // RPC 先行（fail-closed）：Try 失败不落冻结单；超时重放由参与者分支日志幂等吸收
        accountingTcc.tryAction(branch(request.getBizNo(), account.getId(), request.getAmount(), reason)).orThrow();
        long now = System.currentTimeMillis();
        AccountFreezeDO freeze = new AccountFreezeDO();
        freeze.setId("AF" + freezeMapper.nextId());
        freeze.setAccountId(account.getId());
        freeze.setAmount(request.getAmount());
        freeze.setReasonType(reason.name());
        freeze.setReason(request.getReason());
        freeze.setStatus("ACTIVE");
        freeze.setOperator(request.getOperator());
        freeze.setBizNo(request.getBizNo());
        freeze.setCreatedAt(now);
        try {
            freezeMapper.insert(freeze);
        } catch (DuplicateKeyException e) {
            AccountFreezeDO winner = freezeMapper.selectByBizNo(request.getBizNo());
            assertSame(winner, request, reason);
            return toDTO(winner);
        }
        freezeLogService.log(account.getId(), "AMOUNT_FREEZE", null, reason.name(), request.getReason(),
                request.getAmount(), request.getOperator(), null, request.getBizNo());
        log.info("金额冻结 freezeId={} accountId={} amount={} reason={} bizNo={}", freeze.getId(),
                account.getId(), request.getAmount(), reason, request.getBizNo());
        return toDTO(freeze);
    }

    public AccountFreezeDTO unfreeze(UnfreezeRequest request) {
        if (request == null || request.getBizNo() == null || request.getBizNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填（幂等键）");
        }
        FreezeReason reason = parseReason(request.getReasonType());
        AccountFreezeDO freeze = locate(request);
        if (!reason.name().equals(freeze.getReasonType())) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "解冻原因与冻结原因不符: 冻结=" + freeze.getReasonType() + " 请求=" + reason);
        }
        if (FreezeReason.JUDICIAL == reason) {
            String authorizedBy = request.getAuthorizedBy();
            if (authorizedBy == null || authorizedBy.isBlank() || authorizedBy.equals(request.getOperator())) {
                throw new BizException(ResultCode.FORBIDDEN, "司法冻结解冻需双人复核（authorizedBy ≠ operator）");
            }
        }
        if ("RELEASED".equals(freeze.getStatus())) {
            if (request.getBizNo().equals(freeze.getReleaseBizNo())) {
                return toDTO(freeze);
            }
            throw new BizException(ResultCode.CONFLICT, "冻结单已释放: " + freeze.getId());
        }
        // Cancel 幂等（参与者模板：CANCELLED 重放直接返回；无 Try 记录空回滚保护）
        accountingTcc.cancel(branch(freeze.getBizNo(), freeze.getAccountId(), freeze.getAmount(), reason)).orThrow();
        long now = System.currentTimeMillis();
        if (freezeMapper.release(freeze.getId(), now, request.getBizNo(), request.getAuthorizedBy()) == 0) {
            return toDTO(freezeMapper.selectById(freeze.getId()));
        }
        freezeLogService.log(freeze.getAccountId(), "AMOUNT_UNFREEZE", null, reason.name(), freeze.getReason(),
                freeze.getAmount(), request.getOperator(), request.getAuthorizedBy(), request.getBizNo());
        log.info("金额解冻 freezeId={} accountId={} amount={} reason={} releaseBizNo={}", freeze.getId(),
                freeze.getAccountId(), freeze.getAmount(), reason, request.getBizNo());
        return toDTO(freezeMapper.selectById(freeze.getId()));
    }

    public List<AccountFreezeDTO> list(String accountId, String status) {
        List<AccountFreezeDO> freezes = status == null || status.isBlank()
                ? freezeMapper.selectByAccount(accountId)
                : freezeMapper.selectByAccountAndStatus(accountId, status.trim().toUpperCase());
        return freezes.stream().map(AccountFreezeService::toDTO).toList();
    }

    // ------------------------------------------------------------------ internal

    private AccountFreezeDO locate(UnfreezeRequest request) {
        AccountFreezeDO freeze = null;
        if (request.getFreezeId() != null && !request.getFreezeId().isBlank()) {
            freeze = freezeMapper.selectById(request.getFreezeId());
        } else if (request.getAccountId() != null && !request.getAccountId().isBlank()) {
            freeze = freezeMapper.selectActiveByAccountAndReason(request.getAccountId(),
                    parseReason(request.getReasonType()).name());
        } else {
            throw new BizException(ResultCode.PARAM_ERROR, "freezeId 或 accountId+reasonType 必填");
        }
        if (freeze == null) {
            throw new BizException(ResultCode.NOT_FOUND, "无对应 ACTIVE 冻结单");
        }
        return freeze;
    }

    private AccountDO requireActive(String accountId) {
        AccountDO account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "账户非 ACTIVE（状态冻结中），不可金额冻结: " + accountId);
        }
        return account;
    }

    private static void assertSame(AccountFreezeDO existing, FreezeRequest request, FreezeReason reason) {
        if (!existing.getAccountId().equals(request.getAccountId())
                || !existing.getAmount().equals(request.getAmount())
                || !existing.getReasonType().equals(reason.name())) {
            throw new BizException(ResultCode.CONFLICT, "bizNo 已用于不同冻结请求（幂等拒绝）: " + request.getBizNo());
        }
    }

    private static FreezeReason parseReason(String value) {
        try {
            return FreezeReason.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
        }
    }

    private static TccBranchRequest branch(String bizNo, String accountId, long amount, FreezeReason reason) {
        AccountingTccContext ctx = new AccountingTccContext();
        ctx.setBizNo(XID_PREFIX + bizNo);
        ctx.setSubject("行政冻结/" + reason);
        ctx.setReversed(false);
        TransferLineDTO debit = new TransferLineDTO();
        debit.setAccountId(accountId);
        debit.setDir("D");
        debit.setAmount(amount);
        TransferLineDTO credit = new TransferLineDTO();
        credit.setAccountId(SYS_FREEZE);
        credit.setDir("C");
        credit.setAmount(amount);
        ctx.setLines(List.of(debit, credit));
        TccBranchRequest request = new TccBranchRequest();
        request.setXid(XID_PREFIX + bizNo);
        request.setBranchId(bizNo);
        request.setAction("accounting");
        request.setContext(TccContextUtil.toJson(ctx));
        return request;
    }

    private static AccountFreezeDTO toDTO(AccountFreezeDO freeze) {
        AccountFreezeDTO dto = new AccountFreezeDTO();
        dto.setId(freeze.getId());
        dto.setAccountId(freeze.getAccountId());
        dto.setAmount(freeze.getAmount());
        dto.setReasonType(freeze.getReasonType());
        dto.setReason(freeze.getReason());
        dto.setStatus(freeze.getStatus());
        dto.setOperator(freeze.getOperator());
        dto.setAuthorizedBy(freeze.getAuthorizedBy());
        dto.setBizNo(freeze.getBizNo());
        dto.setCreatedAt(freeze.getCreatedAt());
        dto.setReleasedAt(freeze.getReleasedAt());
        dto.setReleaseBizNo(freeze.getReleaseBizNo());
        return dto;
    }
}