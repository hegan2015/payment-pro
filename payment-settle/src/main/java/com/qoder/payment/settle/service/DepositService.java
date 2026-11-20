package com.qoder.payment.settle.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.util.MoneyUtil;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.settle.DepositRecordDTO;
import com.qoder.payment.facade.settle.DepositRequest;
import com.qoder.payment.facade.settle.DepositSummaryDTO;
import com.qoder.payment.settle.mapper.DepositMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ST-6 保证金（缴存 / 释放 / 扣划），<b>全部经既有 AccountingFacade.post 复式记账，不自建账本</b>。
 *
 * <p><b>账务口径</b>（单币种 CNY，D=C 恒成立，Σbalance=0 不回归）：
 * <ul>
 *   <li>缴存 {@code DP:<requestId>}：D 出资账户 / C {@code SYS_DEPOSIT}（2006 保证金负债，已登记科目）；</li>
 *   <li>释放 {@code DR:<requestId>}：D SYS_DEPOSIT / C <b>原出资账户</b>（不可指定他户）；</li>
 *   <li>扣划 {@code DD:<requestId>}：D SYS_DEPOSIT / C 去向户（缺省 {@code SYS_FEE} 平台收入，
 *       DynamicConfig {@code settle.deposit.forfeit-account} 可配；给 advanceId 则 C {@code SYS_ADVANCE}
 *       并同步驱动垫资追偿 + 额度释放）。</li>
 * </ul>
 *
 * <p><b>守恒</b>：{@code balance(SYS_DEPOSIT) == Σ缴存 − Σ释放 − Σ扣划}（全局恒等式，误差 0）。
 * <p><b>幂等</b>：(DEPOSIT_FREEZE/DEPOSIT_RELEASE/DEPOSIT_DEDUCT, requestId) 经既有 IdempotentTemplate；
 * 账务 bizNo 由 requestId <b>确定性派生</b>，本地事务回滚后重试也绝不重复动账。
 * <p><b>并发</b>：缴存单 released/deducted 走 CAS（期望原值），超额释放/扣划一律拒绝、绝不动账。
 * <p><b>出资账户</b>：缺省商户 {@code DEPOSIT} 专户（A-1 类型户幂等懒开户），也可显式指定 BALANCE 户。
 * <p><b>ride 红线</b>：只服务新增控制台端点，不介入 payment/既有结算任何路径；手续费口径一字未改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    public static final String SYS_DEPOSIT = "SYS_DEPOSIT";
    public static final String SYS_FEE = "SYS_FEE";
    public static final String TYPE_DEPOSIT = "DEPOSIT";
    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_DEDUCT = "DEDUCT";
    /** 扣划去向（平台收入户）；可热更，缺省复用既有 SYS_FEE（6001），不新增科目。 */
    public static final String KEY_FORFEIT_ACCOUNT = "settle.deposit.forfeit-account";

    private final DepositMapper depositMapper;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;
    private final CreditService creditService;

    @DubboReference(check = false)
    private AccountingFacade accountingFacade;

    @DubboReference(check = false)
    private AccountFacade accountFacade;

    // ------------------------------------------------------------------ 缴存

    public DepositRecordDTO deposit(DepositRequest request) {
        validate(request, true);
        String requestId = request.getRequestId();
        long amount = MoneyUtil.requireValid(request.getAmount());
        String bizNo = "DP:" + requestId;
        return idempotentTemplate.execute("DEPOSIT_FREEZE", requestId, DepositRecordDTO.class, () -> {
            DepositRecordDTO existing = depositMapper.selectByRequestId(requestId);
            if (existing != null) {
                log.info("ST-6 保证金缴存幂等命中 requestId={} depositId={}", requestId, existing.getId());
                return existing;
            }
            AccountDTO account = resolveAccount(request);
            require("ACTIVE".equals(account.getStatus()), "出资账户非 ACTIVE: " + account.getId());
            long balance = SettleSupport.nz(accountingFacade.balance(account.getId()).orThrow());
            require(balance >= amount, "出资账户余额不足: " + account.getId() + " balance=" + balance + " need=" + amount);
            // 先动账（账务 bizNo 确定性幂等）再登记：任一步失败重试都不会重复划转
            post(bizNo, "保证金缴存", null, List.of(line(account.getId(), "D", amount), line(SYS_DEPOSIT, "C", amount)));
            DepositRecordDTO record = new DepositRecordDTO();
            record.setId(support.newId("DP"));
            record.setMerchantId(request.getMerchantId());
            record.setAccountId(account.getId());
            record.setType(TYPE_DEPOSIT);
            record.setAmount(amount);
            record.setReleasedAmount(0L);
            record.setDeductedAmount(0L);
            record.setStatus("FROZEN");
            record.setBizNo(bizNo);
            record.setRequestId(requestId);
            record.setOperator(request.getOperator());
            record.setReason(request.getReason());
            record.setCreatedAt(System.currentTimeMillis());
            depositMapper.insert(record);
            log.info("ST-6 保证金缴存 depositId={} merchantId={} accountId={} amount={} bizNo={}",
                    record.getId(), record.getMerchantId(), account.getId(), amount, bizNo);
            return record;
        });
    }

    // ------------------------------------------------------------------ 释放

    public DepositRecordDTO release(DepositRequest request) {
        return consume(request, TYPE_RELEASE);
    }

    // ------------------------------------------------------------------ 扣划

    public DepositRecordDTO deduct(DepositRequest request) {
        return consume(request, TYPE_DEDUCT);
    }

    private DepositRecordDTO consume(DepositRequest request, String type) {
        validate(request, false);
        String requestId = request.getRequestId();
        long amount = MoneyUtil.requireValid(request.getAmount());
        boolean release = TYPE_RELEASE.equals(type);
        String bizNo = (release ? "DR:" : "DD:") + requestId;
        String idemType = release ? "DEPOSIT_RELEASE" : "DEPOSIT_DEDUCT";
        return idempotentTemplate.execute(idemType, requestId, DepositRecordDTO.class, () -> {
            DepositRecordDTO existing = depositMapper.selectByRequestId(requestId);
            if (existing != null) {
                log.info("ST-6 保证金{}幂等命中 requestId={} opId={}", type, requestId, existing.getId());
                return existing;
            }
            DepositRecordDTO origin = locate(request);
            long released = SettleSupport.nz(origin.getReleasedAmount());
            long deducted = SettleSupport.nz(origin.getDeductedAmount());
            long remaining = SettleSupport.nz(origin.getAmount()) - released - deducted;
            require(amount <= remaining,
                    (release ? "释放" : "扣划") + "超额：本次 " + amount + " > 缴存单剩余 " + remaining
                            + " depositId=" + origin.getId());
            String target;
            if (release) {
                target = origin.getAccountId();
            } else if (notBlank(request.getAdvanceId())) {
                target = CreditService.SYS_ADVANCE;
            } else {
                target = notBlank(request.getTargetAccount()) ? request.getTargetAccount()
                        : dynamicConfig.get(KEY_FORFEIT_ACCOUNT, SYS_FEE);
                require(target.startsWith("SYS_"), "扣划去向必须是平台系统户（SYS_ 前缀）: " + target);
            }
            // 扣划偿还垫资：先校验垫资单可追偿（受理前拒绝，不落悬挂单）
            if (!release && notBlank(request.getAdvanceId())) {
                creditService.requireRecoverable(request.getAdvanceId(), request.getMerchantId(), amount);
            }
            post(bizNo, release ? "保证金释放" : "保证金扣划", origin.getId(),
                    List.of(line(SYS_DEPOSIT, "D", amount), line(target, "C", amount)));
            long newReleased = release ? released + amount : released;
            long newDeducted = release ? deducted : deducted + amount;
            String status = depositStatus(SettleSupport.nz(origin.getAmount()), newReleased, newDeducted);
            if (depositMapper.casConsume(origin.getId(), released, deducted, newReleased, newDeducted, status) == 0) {
                throw new BizException(ResultCode.CONFLICT, "缴存单已变更（并发 CAS 拒绝）: " + origin.getId());
            }
            if (!release && notBlank(request.getAdvanceId())) {
                creditService.applyRecovery(request.getAdvanceId(), amount, bizNo, requestId,
                        request.getOperator(), request.getReason());
            }
            DepositRecordDTO op = new DepositRecordDTO();
            op.setId(support.newId("DP"));
            op.setMerchantId(origin.getMerchantId());
            op.setAccountId(origin.getAccountId());
            op.setType(type);
            op.setRefId(origin.getId());
            op.setAmount(amount);
            op.setReleasedAmount(0L);
            op.setDeductedAmount(0L);
            op.setStatus("SETTLED");
            op.setBizNo(bizNo);
            op.setRequestId(requestId);
            op.setOperator(request.getOperator());
            op.setReason(request.getReason());
            op.setTargetAccount(target);
            op.setAdvanceId(request.getAdvanceId());
            op.setCreatedAt(System.currentTimeMillis());
            depositMapper.insert(op);
            log.info("ST-6 保证金{} opId={} depositId={} amount={} target={} remaining={} bizNo={}",
                    type, op.getId(), origin.getId(), amount, target, remaining - amount, bizNo);
            return op;
        });
    }

    // ------------------------------------------------------------------ 查询/守恒

    public List<DepositRecordDTO> list(String merchantId, String type) {
        List<DepositRecordDTO> rows = support.readYourWrite(() -> notBlank(merchantId)
                ? depositMapper.selectByMerchant(merchantId) : depositMapper.selectAll());
        if (!notBlank(type)) {
            return rows;
        }
        String wanted = type.trim().toUpperCase();
        return rows.stream().filter(r -> wanted.equals(r.getType())).toList();
    }

    public DepositRecordDTO get(String depositId) {
        DepositRecordDTO record = support.readYourWrite(() -> depositMapper.selectById(depositId));
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "保证金单据不存在: " + depositId);
        }
        return record;
    }

    public DepositSummaryDTO summary(String merchantId) {
        List<DepositRecordDTO> rows = list(merchantId, null);
        long deposited = 0;
        long released = 0;
        long deducted = 0;
        for (DepositRecordDTO row : rows) {
            long amount = SettleSupport.nz(row.getAmount());
            switch (row.getType()) {
                case TYPE_DEPOSIT -> deposited += amount;
                case TYPE_RELEASE -> released += amount;
                case TYPE_DEDUCT -> deducted += amount;
                default -> { }
            }
        }
        long globalHeld = held(null);
        long sysBalance = SettleSupport.nz(accountingFacade.balance(SYS_DEPOSIT).orThrow());
        DepositSummaryDTO dto = new DepositSummaryDTO();
        dto.setMerchantId(merchantId);
        dto.setRecordCount(rows.size());
        dto.setDepositedAmount(deposited);
        dto.setReleasedAmount(released);
        dto.setDeductedAmount(deducted);
        dto.setHeldAmount(deposited - released - deducted);
        dto.setSysDepositBalance(sysBalance);
        dto.setGlobalHeldAmount(globalHeld);
        dto.setDiff(sysBalance - globalHeld);
        dto.setBalanced(sysBalance == globalHeld);
        return dto;
    }

    /** 未释放未扣划保证金（merchantId 为空=全局）。 */
    public long held(String merchantId) {
        long deposited;
        long released;
        long deducted;
        if (notBlank(merchantId)) {
            deposited = SettleSupport.nz(depositMapper.sumAmount(merchantId, TYPE_DEPOSIT));
            released = SettleSupport.nz(depositMapper.sumAmount(merchantId, TYPE_RELEASE));
            deducted = SettleSupport.nz(depositMapper.sumAmount(merchantId, TYPE_DEDUCT));
        } else {
            deposited = SettleSupport.nz(depositMapper.sumAmountGlobal(TYPE_DEPOSIT));
            released = SettleSupport.nz(depositMapper.sumAmountGlobal(TYPE_RELEASE));
            deducted = SettleSupport.nz(depositMapper.sumAmountGlobal(TYPE_DEDUCT));
        }
        return deposited - released - deducted;
    }

    // ------------------------------------------------------------------ internal

    private DepositRecordDTO locate(DepositRequest request) {
        if (notBlank(request.getDepositId())) {
            DepositRecordDTO record = depositMapper.selectById(request.getDepositId());
            if (record == null || !TYPE_DEPOSIT.equals(record.getType())) {
                throw new BizException(ResultCode.NOT_FOUND, "缴存单不存在: " + request.getDepositId());
            }
            if (!record.getMerchantId().equals(request.getMerchantId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "缴存单商户与请求不一致");
            }
            return record;
        }
        List<DepositRecordDTO> available = depositMapper.selectAvailable(request.getMerchantId());
        if (available.isEmpty()) {
            throw new BizException(ResultCode.BIZ_ERROR, "无可释放/扣划的保证金缴存单: " + request.getMerchantId());
        }
        return available.get(0);
    }

    private AccountDTO resolveAccount(DepositRequest request) {
        if (notBlank(request.getAccountId())) {
            AccountDTO account = accountFacade.get(request.getAccountId()).orThrow();
            if (account == null) {
                throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + request.getAccountId());
            }
            if (!account.getMemberId().equals(request.getMerchantId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "出资账户不属于该商户");
            }
            return account;
        }
        // A-1 DEPOSIT 专户幂等懒开户（uk=member_id+account_type+currency，既有户不受影响）
        return accountFacade.createTyped(request.getMerchantId(), "DEPOSIT").orThrow();
    }

    private static String depositStatus(long amount, long released, long deducted) {
        long remaining = amount - released - deducted;
        if (remaining > 0) {
            return released == 0 && deducted == 0 ? "FROZEN" : "PARTIAL";
        }
        if (released > 0 && deducted > 0) {
            return "MIXED";
        }
        return deducted > 0 ? "DEDUCTED" : "RELEASED";
    }

    private void validate(DepositRequest request, boolean deposit) {
        require(request != null && notBlank(request.getMerchantId()), "merchantId 必填");
        require(notBlank(request.getRequestId()), "requestId 必填（幂等键）");
        require(request.getRequestId().length() <= 60, "requestId 需 ≤ 60 字符（账务幂等键派生）");
        require(request.getAmount() != null, "amount 必填");
        if (!deposit) {
            require(notBlank(request.getDepositId()) || notBlank(request.getMerchantId()),
                    "depositId 或 merchantId 必填");
        }
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