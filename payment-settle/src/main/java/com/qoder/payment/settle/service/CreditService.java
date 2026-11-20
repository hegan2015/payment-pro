package com.qoder.payment.settle.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.settle.AdvanceRecordDTO;
import com.qoder.payment.facade.settle.AdvanceRequest;
import com.qoder.payment.facade.settle.CreditAccountDTO;
import com.qoder.payment.facade.settle.CreditSummaryDTO;
import com.qoder.payment.facade.settle.FundExposureDTO;
import com.qoder.payment.settle.mapper.AdvanceMapper;
import com.qoder.payment.settle.mapper.CreditMapper;
import com.qoder.payment.settle.mapper.DepositMapper;
import com.qoder.payment.settle.mapper.WithdrawOrderMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ST-6 授信 / 垫资（平台先垫付后追偿），<b>全部经既有 AccountingFacade.post 复式记账，不自建账本</b>。
 *
 * <p><b>账务口径</b>（单币种 CNY，D=C 恒成立，Σbalance=0 不回归）：
 * <ul>
 *   <li>垫资 {@code AD:<requestId>}：D {@code SYS_ADVANCE} / C 商户账户 —— 平台垫资应收（资产语义，
 *       与 SYS_CASH 同口径：借增 ⇒ 余额为负），商户先用平台资金；</li>
 *   <li>追偿 {@code AR:<requestId>}：D 商户账户 / C SYS_ADVANCE —— 应收回收，额度同步释放；</li>
 *   <li>保证金扣划偿还垫资 {@code DD:<requestId>}：D SYS_DEPOSIT / C SYS_ADVANCE（见 DepositService）。</li>
 * </ul>
 *
 * <p><b>守恒</b>：{@code credit.used == Σ垫资 − Σ追偿 == −balance(SYS_ADVANCE)}（误差 0）。
 * <p><b>并发不超授信</b>：额度占用走 {@code UPDATE ... WHERE used+amount<=credit_limit}（DB 行锁内判定，
 * 原子扣减），超限直接拒绝、<b>绝不动账、不落悬挂单</b>。
 * <p><b>幂等</b>：(ADVANCE/ADVANCE_RECOVER, requestId) 经既有 IdempotentTemplate；账务 bizNo 确定性派生，
 * 本地事务回滚后重试不重复动账（与 ST-3/ST-4 同一模板同一口径）。
 * <p><b>科目</b>：SYS_ADVANCE 为新增系统户（SYS_ 前缀透支语义沿用），当前落 AC-2 既有
 * {@code 2999 其他待映射}；建议主会话补 {@code 1002 垫资应收款}（资产）固定映射，本域无需改代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    public static final String SYS_ADVANCE = "SYS_ADVANCE";
    public static final String TYPE_ADVANCE = "ADVANCE";
    public static final String TYPE_RECOVER = "RECOVER";

    private final CreditMapper creditMapper;
    private final AdvanceMapper advanceMapper;
    private final DepositMapper depositMapper;
    private final WithdrawOrderMapper withdrawOrderMapper;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;

    @DubboReference(check = false)
    private AccountingFacade accountingFacade;

    @DubboReference(check = false)
    private AccountFacade accountFacade;

    // ------------------------------------------------------------------ 授信

    public CreditAccountDTO grant(CreditAccountDTO credit) {
        require(credit != null && notBlank(credit.getMerchantId()), "merchantId 必填");
        require(credit.getCreditLimit() != null && credit.getCreditLimit() >= 0, "creditLimit 必须 ≥ 0");
        String status = notBlank(credit.getStatus()) ? credit.getStatus().trim().toUpperCase() : "ACTIVE";
        require("ACTIVE".equals(status) || "FROZEN".equals(status), "status 仅支持 ACTIVE/FROZEN");
        long now = System.currentTimeMillis();
        CreditAccountDTO existing = creditMapper.selectByMerchant(credit.getMerchantId());
        if (existing == null) {
            CreditAccountDTO row = new CreditAccountDTO();
            row.setMerchantId(credit.getMerchantId());
            row.setCreditLimit(credit.getCreditLimit());
            row.setUsed(0L);
            row.setStatus(status);
            row.setVersion(0);
            row.setOperator(credit.getOperator());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                creditMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 并发授信：对手已插入，改走额度维护
                return updateLimit(credit, status, now);
            }
            log.info("ST-6 授信开立 merchantId={} limit={} status={}", row.getMerchantId(), row.getCreditLimit(), status);
            return creditMapper.selectByMerchant(credit.getMerchantId());
        }
        return updateLimit(credit, status, now);
    }

    private CreditAccountDTO updateLimit(CreditAccountDTO credit, String status, long now) {
        if (creditMapper.updateLimit(credit.getMerchantId(), credit.getCreditLimit(), status,
                credit.getOperator(), now) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "授信额度不得低于已占用额度: merchantId=" + credit.getMerchantId()
                            + " limit=" + credit.getCreditLimit());
        }
        log.info("ST-6 授信维护 merchantId={} limit={} status={}", credit.getMerchantId(), credit.getCreditLimit(), status);
        return get(credit.getMerchantId());
    }

    public CreditAccountDTO get(String merchantId) {
        require(notBlank(merchantId), "merchantId 必填");
        CreditAccountDTO credit = support.readYourWrite(() -> creditMapper.selectByMerchant(merchantId));
        if (credit == null) {
            throw new BizException(ResultCode.NOT_FOUND, "商户未授信: " + merchantId);
        }
        return credit;
    }

    // ------------------------------------------------------------------ 垫资

    public AdvanceRecordDTO advance(AdvanceRequest request) {
        validate(request);
        String requestId = request.getRequestId();
        long amount = MoneyUtil.requireValid(request.getAmount());
        String bizNo = "AD:" + requestId;
        return idempotentTemplate.execute("ADVANCE", requestId, AdvanceRecordDTO.class, () -> {
            AdvanceRecordDTO existing = advanceMapper.selectByRequestId(requestId);
            if (existing != null) {
                log.info("ST-6 垫资幂等命中 requestId={} advanceId={}", requestId, existing.getId());
                return existing;
            }
            CreditAccountDTO credit = creditMapper.selectByMerchant(request.getMerchantId());
            if (credit == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "商户未授信，禁止垫资: " + request.getMerchantId());
            }
            require("ACTIVE".equals(credit.getStatus()), "授信已冻结，禁止新垫资: " + credit.getMerchantId());
            AccountDTO account = resolveAccount(request);
            require("ACTIVE".equals(account.getStatus()), "垫资入账户非 ACTIVE: " + account.getId());
            long now = System.currentTimeMillis();
            // 原子占用额度（DB 行锁内判定 used+amount<=limit）：超限拒绝，绝不动账
            if (creditMapper.casReserve(request.getMerchantId(), amount, now) == 0) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "超授信额度或授信状态不可用: merchantId=" + request.getMerchantId()
                                + " used=" + credit.getUsed() + " limit=" + credit.getCreditLimit() + " need=" + amount);
            }
            post(bizNo, "平台垫资", null, List.of(line(SYS_ADVANCE, "D", amount), line(account.getId(), "C", amount)));
            AdvanceRecordDTO record = new AdvanceRecordDTO();
            record.setId(support.newId("AD"));
            record.setMerchantId(request.getMerchantId());
            record.setAccountId(account.getId());
            record.setType(TYPE_ADVANCE);
            record.setAmount(amount);
            record.setRecoveredAmount(0L);
            record.setStatus("ADVANCED");
            record.setBizNo(bizNo);
            record.setRequestId(requestId);
            record.setOperator(request.getOperator());
            record.setReason(request.getReason());
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            advanceMapper.insert(record);
            log.info("ST-6 垫资 advanceId={} merchantId={} accountId={} amount={} bizNo={}",
                    record.getId(), record.getMerchantId(), account.getId(), amount, bizNo);
            return record;
        });
    }

    // ------------------------------------------------------------------ 追偿

    public AdvanceRecordDTO recover(AdvanceRequest request) {
        validate(request);
        require(notBlank(request.getAdvanceId()), "advanceId 必填（追偿指定垫资单）");
        String requestId = request.getRequestId();
        long amount = MoneyUtil.requireValid(request.getAmount());
        String bizNo = "AR:" + requestId;
        return idempotentTemplate.execute("ADVANCE_RECOVER", requestId, AdvanceRecordDTO.class, () -> {
            AdvanceRecordDTO existing = advanceMapper.selectByRequestId(requestId);
            if (existing != null) {
                log.info("ST-6 垫资追偿幂等命中 requestId={} recoverId={}", requestId, existing.getId());
                return advanceMapper.selectById(existing.getRefId());
            }
            AdvanceRecordDTO advance = requireRecoverable(request.getAdvanceId(), request.getMerchantId(), amount);
            post(bizNo, "垫资追偿", advance.getId(),
                    List.of(line(advance.getAccountId(), "D", amount), line(SYS_ADVANCE, "C", amount)));
            applyRecovery(advance.getId(), amount, bizNo, requestId, request.getOperator(), request.getReason());
            log.info("ST-6 垫资追偿 advanceId={} amount={} bizNo={}", advance.getId(), amount, bizNo);
            return advanceMapper.selectById(advance.getId());
        });
    }

    /** 追偿前置校验（受理前拒绝，不落悬挂单）：单据存在/类型/商户/剩余额度/授信占用。 */
    public AdvanceRecordDTO requireRecoverable(String advanceId, String merchantId, long amount) {
        AdvanceRecordDTO advance = advanceMapper.selectById(advanceId);
        if (advance == null || !TYPE_ADVANCE.equals(advance.getType())) {
            throw new BizException(ResultCode.NOT_FOUND, "垫资单不存在: " + advanceId);
        }
        if (notBlank(merchantId) && !advance.getMerchantId().equals(merchantId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "垫资单商户与请求不一致");
        }
        long remaining = SettleSupport.nz(advance.getAmount()) - SettleSupport.nz(advance.getRecoveredAmount());
        require(amount <= remaining, "追偿超额：本次 " + amount + " > 垫资单未回收 " + remaining + " advanceId=" + advanceId);
        CreditAccountDTO credit = creditMapper.selectByMerchant(advance.getMerchantId());
        if (credit == null || SettleSupport.nz(credit.getUsed()) < amount) {
            throw new BizException(ResultCode.BIZ_ERROR, "授信占用不足，无法回收额度: " + advance.getMerchantId());
        }
        return advance;
    }

    /**
     * 追偿落地（CAS 进度 + 额度释放 + RECOVER 留痕行）。
     * 由 {@link #recover} 与保证金扣划偿还垫资共用；<b>调用方须已完成复式记账</b>（bizNo 传入）。
     */
    public void applyRecovery(String advanceId, long amount, String bizNo, String requestId,
                              String operator, String reason) {
        AdvanceRecordDTO advance = advanceMapper.selectById(advanceId);
        long recovered = SettleSupport.nz(advance.getRecoveredAmount());
        long newRecovered = recovered + amount;
        String status = newRecovered >= SettleSupport.nz(advance.getAmount()) ? "RECOVERED" : "PARTIAL";
        long now = System.currentTimeMillis();
        if (advanceMapper.casRecover(advanceId, recovered, newRecovered, status, now) == 0) {
            throw new BizException(ResultCode.CONFLICT, "垫资单已变更（并发 CAS 拒绝）: " + advanceId);
        }
        if (creditMapper.casRelease(advance.getMerchantId(), amount, now) == 0) {
            throw new BizException(ResultCode.CONFLICT, "授信额度释放失败（占用不足）: " + advance.getMerchantId());
        }
        AdvanceRecordDTO op = new AdvanceRecordDTO();
        op.setId(support.newId("AD"));
        op.setMerchantId(advance.getMerchantId());
        op.setAccountId(advance.getAccountId());
        op.setType(TYPE_RECOVER);
        op.setRefId(advanceId);
        op.setAmount(amount);
        op.setRecoveredAmount(0L);
        op.setStatus("SETTLED");
        op.setBizNo(bizNo);
        op.setRequestId(requestId);
        op.setOperator(operator);
        op.setReason(reason);
        op.setCreatedAt(now);
        op.setUpdatedAt(now);
        advanceMapper.insert(op);
    }

    // ------------------------------------------------------------------ 查询/守恒

    public AdvanceRecordDTO getAdvance(String advanceId) {
        AdvanceRecordDTO record = support.readYourWrite(() -> advanceMapper.selectById(advanceId));
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "垫资单据不存在: " + advanceId);
        }
        return record;
    }

    public List<AdvanceRecordDTO> list(String merchantId, String type) {
        List<AdvanceRecordDTO> rows = support.readYourWrite(() -> notBlank(merchantId)
                ? advanceMapper.selectByMerchant(merchantId) : advanceMapper.selectAll());
        if (!notBlank(type)) {
            return rows;
        }
        String wanted = type.trim().toUpperCase();
        return rows.stream().filter(r -> wanted.equals(r.getType())).toList();
    }

    /** 未回收垫资（merchantId 为空=全局）。 */
    public long outstanding(String merchantId) {
        long advanced;
        long recovered;
        if (notBlank(merchantId)) {
            advanced = SettleSupport.nz(advanceMapper.sumAmount(merchantId, TYPE_ADVANCE));
            recovered = SettleSupport.nz(advanceMapper.sumAmount(merchantId, TYPE_RECOVER));
        } else {
            advanced = SettleSupport.nz(advanceMapper.sumAmountGlobal(TYPE_ADVANCE));
            recovered = SettleSupport.nz(advanceMapper.sumAmountGlobal(TYPE_RECOVER));
        }
        return advanced - recovered;
    }

    public CreditSummaryDTO summary(String merchantId) {
        require(notBlank(merchantId), "merchantId 必填");
        CreditAccountDTO credit = creditMapper.selectByMerchant(merchantId);
        long advanced = SettleSupport.nz(advanceMapper.sumAmount(merchantId, TYPE_ADVANCE));
        long recovered = SettleSupport.nz(advanceMapper.sumAmount(merchantId, TYPE_RECOVER));
        long outstanding = advanced - recovered;
        long globalOutstanding = outstanding(null);
        long sysOutstanding = -SettleSupport.nz(accountingFacade.balance(SYS_ADVANCE).orThrow());
        long used = credit == null ? 0L : SettleSupport.nz(credit.getUsed());
        long limit = credit == null ? 0L : SettleSupport.nz(credit.getCreditLimit());
        CreditSummaryDTO dto = new CreditSummaryDTO();
        dto.setMerchantId(merchantId);
        dto.setCreditLimit(limit);
        dto.setUsed(used);
        dto.setAvailable(limit - used);
        dto.setStatus(credit == null ? "NONE" : credit.getStatus());
        dto.setAdvancedAmount(advanced);
        dto.setRecoveredAmount(recovered);
        dto.setOutstanding(outstanding);
        dto.setSysAdvanceOutstanding(sysOutstanding);
        dto.setGlobalOutstanding(globalOutstanding);
        dto.setUsedMatched(used == outstanding);
        dto.setBalanced(sysOutstanding == globalOutstanding);
        dto.setDiff(sysOutstanding - globalOutstanding);
        return dto;
    }

    /** A-5 销户护栏：会员级保证金/垫资敞口 + 账户级在途提现（只读）。 */
    public FundExposureDTO exposure(String memberId, String accountId) {
        require(notBlank(memberId), "memberId 必填");
        long depositHeld = SettleSupport.nz(depositMapper.sumAmount(memberId, DepositService.TYPE_DEPOSIT))
                - SettleSupport.nz(depositMapper.sumAmount(memberId, DepositService.TYPE_RELEASE))
                - SettleSupport.nz(depositMapper.sumAmount(memberId, DepositService.TYPE_DEDUCT));
        long advanceOutstanding = outstanding(memberId);
        int withdrawInFlight = notBlank(accountId)
                ? support.readYourWrite(() -> withdrawOrderMapper.selectByAccountAndStatus(accountId, "PROCESSING")).size()
                : 0;
        FundExposureDTO dto = new FundExposureDTO();
        dto.setMemberId(memberId);
        dto.setAccountId(accountId);
        dto.setDepositHeld(depositHeld);
        dto.setAdvanceOutstanding(advanceOutstanding);
        dto.setWithdrawInFlight(withdrawInFlight);
        dto.setTotalAmount(depositHeld + advanceOutstanding);
        dto.setClear(depositHeld == 0 && advanceOutstanding == 0 && withdrawInFlight == 0);
        return dto;
    }

    // ------------------------------------------------------------------ internal

    private AccountDTO resolveAccount(AdvanceRequest request) {
        if (notBlank(request.getAccountId())) {
            AccountDTO account = accountFacade.get(request.getAccountId()).orThrow();
            if (account == null) {
                throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + request.getAccountId());
            }
            if (!account.getMemberId().equals(request.getMerchantId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "垫资账户不属于该商户");
            }
            return account;
        }
        AccountDTO account = accountFacade.getByMember(request.getMerchantId()).orThrow();
        if (account == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "商户结算账户缺失: " + request.getMerchantId());
        }
        return account;
    }

    private void validate(AdvanceRequest request) {
        require(request != null && notBlank(request.getMerchantId()), "merchantId 必填");
        require(notBlank(request.getRequestId()), "requestId 必填（幂等键）");
        require(request.getRequestId().length() <= 60, "requestId 需 ≤ 60 字符（账务幂等键派生）");
        require(request.getAmount() != null, "amount 必填");
    }

    private void post(String bizNo, String subject, String refId, List<TransferLineDTO> lines) {
        PostRequest request = new PostRequest();
        request.setBizNo(bizNo);
        request.setSubject(subject);
        request.setRefId(refId);
        request.setLines(lines);
        accountingFacade.post(request).orThrow();
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ResultCode.PARAM_ERROR, message);
        }
    }
}