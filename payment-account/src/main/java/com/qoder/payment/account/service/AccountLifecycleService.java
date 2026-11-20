package com.qoder.payment.account.service;

import com.qoder.payment.account.convert.AccountConverter;
import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.dataobject.AccountLifecycleLogDO;
import com.qoder.payment.account.level.AccountLevelService;
import com.qoder.payment.account.mapper.AccountFreezeMapper;
import com.qoder.payment.account.mapper.AccountLifecycleMapper;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountCloseRequest;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountLifecycleLogDTO;
import com.qoder.payment.facade.account.AccountLevelDTO;
import com.qoder.payment.facade.account.LevelChangeRequest;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.payment.PaymentDTO;
import com.qoder.payment.facade.payment.PaymentFacade;
import com.qoder.payment.facade.settle.FundExposureDTO;
import com.qoder.payment.facade.settle.SettleFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * A-5 账户生命周期（开销户 / 升降级），<b>逻辑销户不物理删</b>。
 *
 * <p><b>状态机</b>：{@code ACTIVE → CLOSING → CLOSED}（终态不可逆；中途护栏失败回 ACTIVE 并留
 * {@code CLOSE_ABORT} 痕）。幂等键 {@code (ACCOUNT_CLOSE, accountId)}（计划 §4.C A-5 口径）：
 * 已 CLOSED 直接返回既有快照，不重复留痕、不重复动账（销户<b>绝不动资金</b>）。
 *
 * <p><b>销户护栏</b>（任一不满足即拒绝，全部服务端权威判定）：
 * <ol>
 *   <li>账务余额 = 0（余额权威在账务域，accountingFacade.balance）；</li>
 *   <li>无 ACTIVE 金额冻结单（A-3 account_freeze）；</li>
 *   <li>账户 status=ACTIVE 且 freezeType=NONE（A-3 状态冻结中不可销户）；</li>
 *   <li>无在途支付单（payment 域非终态单，payer/merchant 任一命中）；</li>
 *   <li>无未释放保证金 / 未回收垫资 / 在途提现（settle 域 ST-6 敞口视图）。</li>
 * </ol>
 * 跨域探测失败一律 <b>fail-closed 拒绝销户</b>（宁可不销，绝不放过在途资金）。
 *
 * <p><b>销户后不可用</b>：CLOSED 时同步把 {@code account.status} 置 CLOSED —— 账务域
 * {@code checkAccounts} 既有校验 {@code !"ACTIVE".equals(status)} 天然拒绝任何记账，
 * <b>无需改动 accounting 一行代码</b>。账户行、余额行、历史分录全部保留（可审计）。
 *
 * <p><b>升降级</b>：升级复用 M-3 既有校验（II 需 KYC 四要素 PASS、III 需 operator 复核）；
 * 降级为 A-5 新增：需 operator 复核 + 目标等级 KYC 门槛 + <b>余额不得超目标等级余额上限</b>，
 * CAS 防并发覆盖，全程 {@code account_lifecycle_log} 留痕。
 *
 * <p><b>ride 红线</b>：本服务只对被显式请求的账户生效；开户幂等/懒开户/余额口径/手续费一字未改，
 * ride 既有账户不会被销户或降级（无自动批量动作、无定时任务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLifecycleService {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSING = "CLOSING";
    public static final String CLOSED = "CLOSED";

    /** 销户前 settle 敞口探测开关（默认开；关闭需显式配置，fail-closed 语义不变）。 */
    public static final String KEY_CHECK_SETTLE = "account.close.check-settle-exposure";
    /** 销户前 payment 在途探测开关（默认开）。 */
    public static final String KEY_CHECK_PAYMENT = "account.close.check-payment-inflight";

    /** 支付终态集合（其余一律视为在途，fail-closed）。 */
    private static final Set<String> PAYMENT_TERMINAL =
            Set.of("SUCCESS", "FAILED", "CLOSED", "CANCELLED", "REFUNDED", "PARTIAL_REFUNDED");
    private static final List<String> LEVELS = List.of("I", "II", "III");

    private final AccountMapper accountMapper;
    private final AccountLifecycleMapper lifecycleMapper;
    private final AccountFreezeMapper freezeMapper;
    private final AccountLevelService levelService;
    private final AccountService accountService;
    private final DynamicConfig dynamicConfig;

    @DubboReference(check = false)
    private AccountingFacade accountingFacade;

    @DubboReference(check = false, timeout = 10000, retries = 0)
    private SettleFacade settleFacade;

    @DubboReference(check = false, timeout = 10000, retries = 0)
    private PaymentFacade paymentFacade;

    // ------------------------------------------------------------------ 销户

    public AccountDTO close(AccountCloseRequest request) {
        if (request == null || blank(request.getAccountId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        if (blank(request.getRequestId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 必填（幂等键）");
        }
        if (request.getRequestId().length() > 60) {
            throw new BizException(ResultCode.PARAM_ERROR, "requestId 需 ≤ 60 字符");
        }
        AccountDO account = require(request.getAccountId());
        String lifecycle = lifecycleOf(account);
        // 幂等：已销户直接返回既有快照（不重复留痕、不重复校验、绝不动资金）
        if (CLOSED.equals(lifecycle)) {
            log.info("A-5 销户幂等命中 accountId={} requestId={}", account.getId(), request.getRequestId());
            return accountService.get(account.getId());
        }
        String operator = blank(request.getOperator()) ? "console" : request.getOperator();
        // 护栏（受理前拒绝，不落悬挂单）
        guardClosable(account, request.getRequestId());

        if (ACTIVE.equals(lifecycle)) {
            if (accountMapper.casLifecycle(account.getId(), ACTIVE, CLOSING, null) == 0) {
                throw new BizException(ResultCode.CONFLICT, "账户生命周期已变更（并发 CAS 拒绝）: " + account.getId());
            }
            writeLog(account.getId(), "CLOSE_REQUEST", ACTIVE, CLOSING, null, null,
                    operator, request.getReason(), request.getRequestId());
        }
        // CLOSING 期间复核（并发动账/冻结在标记后到达 ⇒ 回滚生命周期，绝不带着资金销户）
        try {
            guardClosable(account, request.getRequestId());
        } catch (RuntimeException e) {
            accountMapper.casLifecycle(account.getId(), CLOSING, ACTIVE, null);
            writeLog(account.getId(), "CLOSE_ABORT", CLOSING, ACTIVE, null, null,
                    operator, "销户复核失败: " + e.getMessage(), request.getRequestId());
            throw e;
        }
        long now = System.currentTimeMillis();
        if (accountMapper.casLifecycle(account.getId(), CLOSING, CLOSED, now) == 0) {
            throw new BizException(ResultCode.CONFLICT, "账户销户终态 CAS 失败（并发）: " + account.getId());
        }
        writeLog(account.getId(), "CLOSE", CLOSING, CLOSED, null, null,
                operator, request.getReason(), request.getRequestId());
        log.info("A-5 账户销户（逻辑销户，账户行保留）accountId={} memberId={} operator={} requestId={}",
                account.getId(), account.getMemberId(), operator, request.getRequestId());
        return accountService.get(account.getId());
    }

    /** 销户护栏：余额 0 + 无冻结 + 状态 ACTIVE + 无在途支付 + 无 settle 资金敞口。 */
    private void guardClosable(AccountDO account, String requestId) {
        AccountDO fresh = require(account.getId());
        if (CLOSED.equals(lifecycleOf(fresh))) {
            return;
        }
        if (!ACTIVE.equals(fresh.getStatus())) {
            throw new BizException(ResultCode.CONFLICT,
                    "账户状态非 ACTIVE（冻结/已销户），不可销户: " + fresh.getId() + " status=" + fresh.getStatus());
        }
        if (fresh.getFreezeType() != null && !"NONE".equals(fresh.getFreezeType())) {
            throw new BizException(ResultCode.CONFLICT,
                    "账户存在状态冻结（" + fresh.getFreezeType() + "），不可销户: " + fresh.getId());
        }
        long balance = accountingFacade.balance(fresh.getId()).orThrow();
        int activeFreezes = freezeMapper.selectByAccountAndStatus(fresh.getId(), "ACTIVE").size();
        if (activeFreezes > 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "存在未解冻金额冻结单 " + activeFreezes + " 笔，不可销户: " + fresh.getId());
        }
        if (balance != 0L) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "销户要求账务余额为 0，当前余额=" + balance + " accountId=" + fresh.getId());
        }
        if (dynamicConfig.getBool(KEY_CHECK_PAYMENT, true)) {
            int inFlight = paymentInFlight(fresh.getId());
            if (inFlight > 0) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "存在在途支付单 " + inFlight + " 笔，不可销户: " + fresh.getId());
            }
        }
        if (dynamicConfig.getBool(KEY_CHECK_SETTLE, true)) {
            FundExposureDTO exposure = settleExposure(fresh);
            if (!Boolean.TRUE.equals(exposure.getClear())) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "存在未释放保证金/未回收垫资/在途提现（deposit=" + exposure.getDepositHeld()
                                + " advance=" + exposure.getAdvanceOutstanding()
                                + " withdraw=" + exposure.getWithdrawInFlight() + "），不可销户: " + fresh.getId());
            }
        }
    }

    /** 在途支付探测（只调既有 facade 方法，不给 payment 域新增契约）；RPC 失败 fail-closed。 */
    private int paymentInFlight(String accountId) {
        Result<List<PaymentDTO>> result;
        try {
            result = paymentFacade.listPayments();
        } catch (Exception e) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "在途支付探测不可达，拒绝销户（fail-closed）: " + e.getMessage());
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "在途支付探测失败，拒绝销户（fail-closed）");
        }
        int count = 0;
        for (PaymentDTO payment : result.getData()) {
            boolean mine = accountId.equals(payment.getPayerAccountId())
                    || accountId.equals(payment.getMerchantAccountId());
            if (mine && !PAYMENT_TERMINAL.contains(payment.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private FundExposureDTO settleExposure(AccountDO account) {
        Result<FundExposureDTO> result;
        try {
            result = settleFacade.fundExposure(account.getMemberId(), account.getId());
        } catch (Exception e) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "保证金/垫资敞口探测不可达，拒绝销户（fail-closed）: " + e.getMessage());
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "保证金/垫资敞口探测失败，拒绝销户（fail-closed）");
        }
        return result.getData();
    }

    // ------------------------------------------------------------------ 升降级

    public AccountDTO changeLevel(LevelChangeRequest request) {
        if (request == null || blank(request.getAccountId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        String target = request.getTargetLevel() == null ? "" : request.getTargetLevel().trim().toUpperCase();
        if (!LEVELS.contains(target)) {
            throw new BizException(ResultCode.PARAM_ERROR, "targetLevel 仅支持 I/II/III");
        }
        AccountDO account = require(request.getAccountId());
        String lifecycle = lifecycleOf(account);
        if (!ACTIVE.equals(lifecycle)) {
            throw new BizException(ResultCode.CONFLICT,
                    "账户生命周期非 ACTIVE（" + lifecycle + "），不可升降级: " + account.getId());
        }
        String current = levelOf(account);
        if (current.equals(target)) {
            // 幂等：目标=当前，不重复留痕
            return accountService.get(account.getId());
        }
        String operator = blank(request.getOperator()) ? "console" : request.getOperator();
        boolean upgrade = LEVELS.indexOf(target) > LEVELS.indexOf(current);
        if (upgrade) {
            // 升级完全复用 M-3 既有校验（KYC 四要素 / III 类 operator 复核），语义一字未改
            levelService.upgradeLevel(account.getId(), target, request.getOperator());
            writeLog(account.getId(), "UPGRADE", null, null, current, target,
                    operator, request.getReason(), null);
            log.info("A-5 账户升级 accountId={} {}→{} operator={}", account.getId(), current, target, operator);
            return accountService.get(account.getId());
        }
        // ---- 降级（A-5 新增）----
        if (blank(request.getOperator())) {
            throw new BizException(ResultCode.FORBIDDEN, "账户降级属敏感操作，需 operator 复核");
        }
        AccountLevelDTO currentLevel = levelService.getLevel(account.getId());
        String kycLevel = currentLevel.getKycLevel();
        if (!"I".equals(target) && !"II".equals(kycLevel) && !"III".equals(kycLevel)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "降级目标等级 " + target + " 仍需 KYC 实名（M-3 联动），当前未实名");
        }
        AccountLevelDTO targetRule = levelService.levelRule(target);
        long balance = accountingFacade.balance(account.getId()).orThrow();
        if (balance > targetRule.getBalanceLimit()) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "降级后余额超目标等级上限: balance=" + balance + " > " + target
                            + " 类余额上限 " + targetRule.getBalanceLimit() + "（请先转出资金）");
        }
        if (accountMapper.casLevel(account.getId(), current, target) == 0) {
            throw new BizException(ResultCode.CONFLICT, "账户等级已变更（并发 CAS 拒绝）: " + account.getId());
        }
        writeLog(account.getId(), "DOWNGRADE", null, null, current, target,
                operator, request.getReason(), null);
        log.info("A-5 账户降级 accountId={} {}→{} operator={} balance={} targetBalanceLimit={}",
                account.getId(), current, target, operator, balance, targetRule.getBalanceLimit());
        return accountService.get(account.getId());
    }

    public List<AccountLifecycleLogDTO> logs(String accountId) {
        if (blank(accountId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        require(accountId);
        return lifecycleMapper.selectByAccount(accountId).stream().map(row -> {
            AccountLifecycleLogDTO dto = new AccountLifecycleLogDTO();
            dto.setId(row.getId());
            dto.setAccountId(row.getAccountId());
            dto.setAction(row.getAction());
            dto.setFromStatus(row.getFromStatus());
            dto.setToStatus(row.getToStatus());
            dto.setFromLevel(row.getFromLevel());
            dto.setToLevel(row.getToLevel());
            dto.setOperator(row.getOperator());
            dto.setReason(row.getReason());
            dto.setBizNo(row.getBizNo());
            dto.setCreatedAt(row.getCreatedAt());
            return dto;
        }).toList();
    }

    // ------------------------------------------------------------------ internal

    private void writeLog(String accountId, String action, String fromStatus, String toStatus,
                          String fromLevel, String toLevel, String operator, String reason, String bizNo) {
        AccountLifecycleLogDO row = new AccountLifecycleLogDO();
        row.setId("AL" + lifecycleMapper.nextId());
        row.setAccountId(accountId);
        row.setAction(action);
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setFromLevel(fromLevel);
        row.setToLevel(toLevel);
        row.setOperator(operator);
        row.setReason(reason);
        row.setBizNo(bizNo);
        row.setCreatedAt(System.currentTimeMillis());
        lifecycleMapper.insert(row);
    }

    private AccountDO require(String accountId) {
        AccountDO account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + accountId);
        }
        return account;
    }

    /** 存量行/缺省归一：NULL/空白 → ACTIVE（既有账户行为逐字节不变）。 */
    public static String lifecycleOf(AccountDO account) {
        return account.getLifecycleStatus() == null || account.getLifecycleStatus().isBlank()
                ? ACTIVE : account.getLifecycleStatus();
    }

    private static String levelOf(AccountDO account) {
        return account.getLevel() == null || account.getLevel().isBlank() ? "I" : account.getLevel();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}